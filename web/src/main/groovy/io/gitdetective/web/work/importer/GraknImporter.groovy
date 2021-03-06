package io.gitdetective.web.work.importer

import ai.grakn.GraknSession
import ai.grakn.GraknTxType
import ai.grakn.Keyspace
import ai.grakn.client.Grakn
import ai.grakn.graql.Query
import ai.grakn.graql.answer.ConceptMap
import ai.grakn.util.SimpleURI
import com.google.common.base.Charsets
import com.google.common.io.Resources
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import io.gitdetective.web.WebLauncher
import io.gitdetective.web.dao.GraknDAO
import io.gitdetective.web.dao.RedisDAO
import io.gitdetective.web.dao.storage.ReferenceStorage
import io.vertx.blueprint.kue.Kue
import io.vertx.blueprint.kue.queue.Job
import io.vertx.core.*
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory

import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipFile

import static io.gitdetective.web.WebServices.*

/**
 * Import augmented and filtered/funnelled Kythe compilation data into Grakn
 *
 * @author <a href="mailto:brandon.fergerson@codebrig.com">Brandon Fergerson</a>
 */
class GraknImporter extends AbstractVerticle {

    public static final String GRAKN_INDEX_IMPORT_JOB_TYPE = "ImportGithubProject"
    private final static Logger log = LoggerFactory.getLogger(GraknImporter.class)
    private final static String GET_FILE = Resources.toString(Resources.getResource(
            "queries/import/get_file.gql"), Charsets.UTF_8)
    private final static String IMPORT_FILES = Resources.toString(Resources.getResource(
            "queries/import/import_files.gql"), Charsets.UTF_8)
    private final static String GET_DEFINITION_BY_FILE_NAME = Resources.toString(Resources.getResource(
            "queries/import/get_definition_by_file_name.gql"), Charsets.UTF_8)
    private final static String GET_INTERNAL_REFERENCE = Resources.toString(Resources.getResource(
            "queries/import/get_internal_reference.gql"), Charsets.UTF_8)
    private final static String GET_INTERNAL_REFERENCE_BY_FUNCTION_NAME = Resources.toString(Resources.getResource(
            "queries/import/get_internal_reference_by_function_name.gql"), Charsets.UTF_8)
    private final static String GET_INTERNAL_REFERENCE_BY_FILE_NAME = Resources.toString(Resources.getResource(
            "queries/import/get_internal_reference_by_file_name.gql"), Charsets.UTF_8)
    private final static String GET_EXTERNAL_REFERENCE_BY_FILE_NAME = Resources.toString(Resources.getResource(
            "queries/import/get_external_reference_by_file_name.gql"), Charsets.UTF_8)
    private final static String GET_EXTERNAL_REFERENCE = Resources.toString(Resources.getResource(
            "queries/import/get_external_reference.gql"), Charsets.UTF_8)
    private final static String IMPORT_DEFINED_FUNCTIONS = Resources.toString(Resources.getResource(
            "queries/import/import_defined_functions.gql"), Charsets.UTF_8)
    private final static String IMPORT_INTERNAL_REFERENCED_FUNCTIONS = Resources.toString(Resources.getResource(
            "queries/import/import_internal_referenced_functions.gql"), Charsets.UTF_8)
    private final static String IMPORT_EXTERNAL_REFERENCED_FUNCTIONS = Resources.toString(Resources.getResource(
            "queries/import/import_external_referenced_functions.gql"), Charsets.UTF_8)
    private final static String IMPORT_EXTERNAL_REFERENCED_FUNCTION_BY_FILE = Resources.toString(Resources.getResource(
            "queries/import/import_external_referenced_function_by_file.gql"), Charsets.UTF_8)
    private final static String IMPORT_FILE_TO_FUNCTION_REFERENCE = Resources.toString(Resources.getResource(
            "queries/import/import_file_to_function_reference.gql"), Charsets.UTF_8)
    private final Kue kue
    private final RedisDAO redis
    private final ReferenceStorage referenceStorage
    private final GraknDAO grakn
    private final String uploadsDirectory
    private GraknSession graknSession

    GraknImporter(Kue kue, RedisDAO redis, ReferenceStorage referenceStorage, GraknDAO grakn, String uploadsDirectory) {
        this.kue = kue
        this.redis = redis
        this.referenceStorage = referenceStorage
        this.grakn = grakn
        this.uploadsDirectory = uploadsDirectory
    }

    @Override
    void start() throws Exception {
        String graknHost = config().getString("grakn.host")
        int graknPort = config().getInteger("grakn.port")
        String graknKeyspace = config().getString("grakn.keyspace")
        def keyspace = Keyspace.of(graknKeyspace)
        def grakn = new Grakn(new SimpleURI(graknHost + ":" + graknPort))
        graknSession = grakn.session(keyspace)

        def importerConfig = config().getJsonObject("importer")
        def graknImportMeter = WebLauncher.metrics.meter("GraknImportJobProcessSpeed")
        kue.on("error", {
            log.error "Import job error: " + it.body()
        })
        kue.process(GRAKN_INDEX_IMPORT_JOB_TYPE, importerConfig.getInteger("thread_count"), { parentJob ->
            graknImportMeter.mark()
            log.info "Import job rate: " + (graknImportMeter.oneMinuteRate * 60) +
                    " per/min - Thread: " + Thread.currentThread().name

            def indexJob = parentJob
            indexJob.removeOnComplete = true
            def job = new Job(parentJob.data)
            vertx.executeBlocking({
                processImportJob(job, it.completer())
            }, false, {
                if (it.failed()) {
                    it.cause().printStackTrace()
                    logPrintln(job, "Import failed! Cause: " + it.cause().message)
                    indexJob.done(it.cause())
                } else {
                    indexJob.done()
                }
            })
        })
        log.info "GraknImporter started"
    }

    @Override
    void stop() throws Exception {
        graknSession?.close()
    }

    private void processImportJob(Job job, Handler<AsyncResult> handler) {
        String githubRepository = job.data.getString("github_repository").toLowerCase()
        log.info "Importing project: " + githubRepository
        try {
            def outputDirectory = downloadAndExtractImportFiles(job)
            importProject(outputDirectory, job, {
                if (it.failed()) {
                    handler.handle(Future.failedFuture(it.cause()))
                } else {
                    finalizeImport(job, githubRepository, handler)
                }
            })
        } catch (ImportTimeoutException e) {
            logPrintln(job, "Project import timed out")
            handler.handle(Future.failedFuture(e))
        } catch (all) {
            all.printStackTrace()
            logPrintln(job, "Project failed to import")
            handler.handle(Future.failedFuture(all))
        }
    }

    private void importProject(File outputDirectory, Job job, Handler<AsyncResult> handler) {
        def timeoutTime = Instant.now().plus(1, ChronoUnit.HOURS)
        def filesOutput = new File(outputDirectory, "files.txt")
        def osFunctionsOutput = new File(outputDirectory, "functions_open-source.txt")
        def functionDefinitions = new File(outputDirectory, "functions_definition.txt")
        def functionReferences = new File(outputDirectory, "functions_reference.txt")
        def githubRepository = job.data.getString("github_repository").toLowerCase()
        def cacheFutures = new ArrayList<Future>()
        def importData = new ImportSessionData()
        boolean newProject = false
        String projectId = null

        def tx = graknSession.transaction(GraknTxType.WRITE)
        try {
            def graql = tx.graql()
            def res = graql.parse(GraknDAO.GET_PROJECT
                    .replace("<githubRepo>", githubRepository)).execute() as List<ConceptMap>
            if (!res.isEmpty()) {
                logPrintln(job, "Updating existing project")
                projectId = res.get(0).get("p").asEntity().id().toString()
            } else {
                newProject = true
                logPrintln(job, "Creating new project")
                def query = graql.parse(GraknDAO.CREATE_PROJECT
                        .replace("<githubRepo>", githubRepository)
                        .replace("<createDate>", Instant.now().toString()))
                projectId = (query.execute() as List<ConceptMap>).get(0).get("p").asEntity().id().toString()
                WebLauncher.metrics.counter("CreateProject").inc()
            }
            tx.commit()
        } catch (all) {
            handler.handle(Future.failedFuture(all))
            return
        } finally {
            tx.close()
        }

        //open source functions
        importOpenSourceFunctions(job, osFunctionsOutput, timeoutTime, importData, cacheFutures)
        //project files
        long fileCount = importFiles(projectId, job, filesOutput, timeoutTime, newProject, importData, cacheFutures)
        //project definitions
        importDefinitions(projectId, job, functionDefinitions, timeoutTime, newProject, importData, cacheFutures, {
            if (it.failed()) {
                handler.handle(Future.failedFuture(it.cause()))
            } else {
                long methodInstanceCount = it.result()

                //project references
                importReferences(projectId, job, functionReferences, timeoutTime, newProject, importData, cacheFutures, {
                    if (it.failed()) {
                        handler.handle(Future.failedFuture(it.cause()))
                    } else {
                        //cache new project/file counts; then finished
                        logPrintln(job, "Caching import data")
                        def cacheInfoTimer = WebLauncher.metrics.timer("CachingProjectInformation")
                        def cacheInfoContext = cacheInfoTimer.time()
                        def fut1 = Future.future()
                        cacheFutures.add(fut1)
                        redis.incrementCachedProjectFileCount(githubRepository, fileCount, fut1.completer())
                        def fut2 = Future.future()
                        cacheFutures.add(fut2)
                        redis.incrementCachedProjectMethodInstanceCount(githubRepository, methodInstanceCount, fut2.completer())
                        CompositeFuture.all(cacheFutures).setHandler({
                            if (it.failed()) {
                                handler.handle(Future.failedFuture(it.cause()))
                            } else {
                                logPrintln(job, "Caching import data took: " + asPrettyTime(cacheInfoContext.stop()))
                                handler.handle(Future.succeededFuture())
                            }
                        })
                    }
                })
            }
        })
    }

    private void importOpenSourceFunctions(Job job, File osFunctionsOutput, Instant timeoutTime,
                                           ImportSessionData importData, List<Future> cacheFutures) {
        def tx
        logPrintln(job, "Importing open source functions")
        def importOSFTimer = WebLauncher.metrics.timer("ImportingOSFunctions")
        def importOSFContext = importOSFTimer.time()
        tx = graknSession.transaction(GraknTxType.BATCH)
        try {
            def graql = tx.graql()
            def lineNumber = 0
            osFunctionsOutput.eachLine {
                if (Instant.now().isAfter(timeoutTime)) throw new ImportTimeoutException()
                lineNumber++
                if (lineNumber > 1) {
                    def lineData = it.split("\\|")
                    def fut = Future.future()
                    cacheFutures.add(fut)
                    def osFunc = grakn.getOrCreateOpenSourceFunction(lineData[0], graql, fut.completer())
                    importData.openSourceFunctions.put(lineData[0], osFunc)
                }
            }
            tx.commit()
        } finally {
            tx.close()
        }
        logPrintln(job, "Importing open source functions took: " + asPrettyTime(importOSFContext.stop()))
    }

    private long importFiles(String projectId, Job job, File filesOutput, Instant timeoutTime, boolean newProject,
                             ImportSessionData importData, List<Future> cacheFutures) {
        long fileCount = 0
        def githubRepository = job.data.getString("github_repository").toLowerCase()
        def tx

        logPrintln(job, "Importing files")
        def importFilesTimer = WebLauncher.metrics.timer("ImportingFiles")
        def importFilesContext = importFilesTimer.time()
        tx = graknSession.transaction(GraknTxType.BATCH)
        try {
            def graql = tx.graql()
            def lineNumber = 0
            filesOutput.eachLine {
                if (Instant.now().isAfter(timeoutTime)) throw new ImportTimeoutException()
                lineNumber++
                if (lineNumber > 1) {
                    def lineData = it.split("\\|")
                    def importFile = newProject

                    if (!newProject) {
                        //find existing file
                        def query = graql.parse(GET_FILE
                                .replace("<filename>", lineData[1])
                                .replace("<projectId>", projectId))
                        def match = query.execute() as List<ConceptMap>
                        importFile = match.isEmpty()
                        if (!importFile) {
                            def existingFileId = match.get(0).get("x").asEntity().id().toString()
                            def fut = Future.future()
                            cacheFutures.add(fut)
                            referenceStorage.addProjectImportedFile(githubRepository, lineData[1], existingFileId, fut.completer())
                            importData.importedFiles.put(lineData[1], existingFileId)
                        }
                    }

                    if (importFile) {
                        def importedFile = graql.parse(IMPORT_FILES
                                .replace("<projectId>", projectId)
                                .replace("<createDate>", Instant.now().toString())
                                .replace("<fileLocation>", lineData[0])
                                .replace("<filename>", lineData[1])
                                .replace("<qualifiedName>", lineData[2])).execute() as List<ConceptMap>
                        def importedFileId = importedFile.get(0).get("f").asEntity().id().toString()
                        importData.importedFiles.put(lineData[1], importedFileId)
                        WebLauncher.metrics.counter("ImportFile").inc()
                        fileCount++

                        //cache imported file
                        def fut = Future.future()
                        cacheFutures.add(fut)
                        referenceStorage.addProjectImportedFile(githubRepository, lineData[1], importedFileId, fut.completer())
                    }
                }
            }
            tx.commit()
        } finally {
            tx.close()
        }
        logPrintln(job, "Importing files took: " + asPrettyTime(importFilesContext.stop()))
        return fileCount
    }

    private void importDefinitions(String projectId, Job job, File functionDefinitions, Instant timeoutTime,
                                   boolean newProject, ImportSessionData importData, List<Future> cacheFutures,
                                   Handler<AsyncResult<Long>> handler) {
        long methodInstanceCount = 0
        def githubRepository = job.data.getString("github_repository").toLowerCase()
        def commitSha1 = job.data.getString("commit")
        def commitDate = job.data.getString("commit_date")
        def tx

        logPrintln(job, "Importing defined functions")
        def importDefinitionsTimer = WebLauncher.metrics.timer("ImportingDefinedFunctions")
        def importDefinitionsContext = importDefinitionsTimer.time()
        def importFutures = new ArrayList<Future>()
        tx = graknSession.transaction(GraknTxType.BATCH)
        try {
            def graql = tx.graql()
            def lineNumber = 0
            functionDefinitions.eachLine {
                if (Instant.now().isAfter(timeoutTime)) throw new ImportTimeoutException()
                lineNumber++
                if (lineNumber > 1) {
                    def lineData = it.split("\\|")
                    def importDefinition = newProject
                    def fileId = importData.importedFiles.get(lineData[0])

                    if (!newProject) {
                        //find existing defined function
                        def existingDef = graql.parse(GET_DEFINITION_BY_FILE_NAME
                                .replace("<functionName>", lineData[2])
                                .replace("<filename>", lineData[0])).execute() as List<ConceptMap>
                        importDefinition = existingDef.isEmpty()
                        if (!importDefinition) {
                            def existingFileId = existingDef.get(0).get("x").asEntity().id().toString()
                            def existingFunctionInstanceId = existingDef.get(0).get("y").asEntity().id().toString()
                            def existingFunctionId = existingDef.get(0).get("z").asEntity().id().toString()
                            def fut1 = Future.future()
                            def fut2 = Future.future()
                            cacheFutures.addAll(fut1, fut2)
                            referenceStorage.addProjectImportedFunction(githubRepository, lineData[1], existingFunctionId, fut1.completer())
                            referenceStorage.addProjectImportedDefinition(existingFileId, existingFunctionId, fut2.completer())

                            importData.definedFunctions.put(lineData[1], existingFunctionId)
                            importData.definedFunctionInstances.put(existingFunctionId, existingFunctionInstanceId)
                        }
                    }

                    if (importDefinition) {
                        def startOffset = lineData[3]
                        def endOffset = lineData[4]
                        def osFunc = importData.openSourceFunctions.get(lineData[1])
                        if (osFunc == null) {
                            def fut = Future.future()
                            cacheFutures.add(fut)
                            osFunc = grakn.getOrCreateOpenSourceFunction(lineData[1], graql, fut.completer())
                            importData.openSourceFunctions.put(lineData[1], osFunc)
                        }
                        if (fileId == null) {
                            //println "todo: me3" //todo: me
                            return
                        }

                        def fut = Future.future()
                        importFutures.add(fut)
                        def importCode = new ImportableSourceCode()
                        importCode.fileId = fileId
                        importCode.functionId = osFunc.functionId
                        importCode.functionName = lineData[1]
                        importCode.functionQualifiedName = lineData[2]
                        importCode.insertQuery = graql.parse(IMPORT_DEFINED_FUNCTIONS
                                .replace("<xFileId>", fileId)
                                .replace("<projectId>", projectId)
                                .replace("<funcDefsId>", osFunc.functionDefinitionsId)
                                .replace("<createDate>", Instant.now().toString())
                                .replace("<qualifiedName>", lineData[2])
                                .replace("<commitSha1>", commitSha1)
                                .replace("<commitDate>", commitDate)
                                .replace("<startOffset>", startOffset)
                                .replace("<endOffset>", endOffset))
                        fut.complete(importCode)
                    }
                }
            }
            tx.commit()
        } catch (all) {
            handler.handle(Future.failedFuture(all))
            return
        } finally {
            tx.close()
        }

        CompositeFuture.all(importFutures).setHandler({
            if (it.failed()) {
                handler.handle(Future.failedFuture(it.cause()))
            } else {
                log.debug "Executing insert queries"
                tx = graknSession.transaction(GraknTxType.BATCH)
                try {
                    def futures = it.result().list() as List<ImportableSourceCode>
                    for (def importCode : futures) {
                        def result = importCode.insertQuery.withTx(tx).execute() as List<ConceptMap>
                        importCode.functionInstanceId = result.get(0).get("y").asEntity().id().toString()
                        importData.definedFunctions.put(importCode.functionName, importCode.functionId)
                        importData.definedFunctionInstances.put(importCode.functionId, importCode.functionInstanceId)
                        WebLauncher.metrics.counter("ImportDefinedFunction").inc()
                        methodInstanceCount++

                        //cache imported function/definition
                        def fut1 = Future.future()
                        def fut2 = Future.future()
                        def fut3 = Future.future()
                        cacheFutures.addAll(fut1, fut2, fut3)
                        referenceStorage.addProjectImportedFunction(githubRepository, importCode.functionName, importCode.functionId, fut1.completer())
                        referenceStorage.addProjectImportedDefinition(importCode.fileId, importCode.functionId, fut2.completer())
                        referenceStorage.addFunctionOwner(importCode.functionId, importCode.functionQualifiedName, githubRepository, fut3.completer())
                    }
                    tx.commit()
                } catch (all) {
                    handler.handle(Future.failedFuture(all))
                    return
                } finally {
                    tx.close()
                }
                logPrintln(job, "Importing defined functions took: " + asPrettyTime(importDefinitionsContext.stop()))
                handler.handle(Future.succeededFuture(methodInstanceCount))
            }
        })
    }

    private void importReferences(String projectId, Job job, File functionReferences, Instant timeoutTime,
                                  boolean newProject, ImportSessionData importData, List<Future> cacheFutures,
                                  Handler<AsyncResult> handler) {
        def githubRepository = job.data.getString("github_repository").toLowerCase()
        def commitSha1 = job.data.getString("commit")
        def commitDate = job.data.getString("commit_date")
        def tx

        logPrintln(job, "Importing function references")
        def importReferencesTimer = WebLauncher.metrics.timer("ImportingReferences")
        def importReferencesContext = importReferencesTimer.time()
        def importFutures = new ArrayList<Future<Query>>()
        tx = graknSession.transaction(GraknTxType.BATCH)
        try {
            def graql = tx.graql()
            def lineNumber = 0
            functionReferences.eachLine {
                if (Instant.now().isAfter(timeoutTime)) throw new ImportTimeoutException()
                lineNumber++
                if (lineNumber > 1) {
                    def lineData = it.split("\\|")
                    def importReference = newProject
                    def isFileReferencing = !lineData[1].contains("#")
                    def isExternal = Boolean.valueOf(lineData[7])
                    def osFunc = importData.openSourceFunctions.get(lineData[3])
                    if (osFunc == null) {
                        def fut = Future.future()
                        cacheFutures.add(fut)
                        osFunc = grakn.getOrCreateOpenSourceFunction(lineData[3], graql, fut.completer())
                        importData.openSourceFunctions.put(lineData[3], osFunc)
                    }

                    if (!newProject) {
                        //find existing reference
                        if (isFileReferencing) {
                            def refFile = lineData[1]
                            if (isExternal) {
                                def existingRef = graql.parse(GET_EXTERNAL_REFERENCE_BY_FILE_NAME
                                        .replace("<xFileName>", refFile)
                                        .replace("<yFuncRefsId>", osFunc.functionReferencesId)).execute() as List<ConceptMap>
                                importReference = existingRef.isEmpty()
                                if (!importReference) {
                                    def fileId = existingRef.get(0).get("file").asEntity().id().toString()
                                    def functionId = existingRef.get(0).get("func").asEntity().id().toString()
                                    def fut = Future.future()
                                    cacheFutures.addAll(fut)
                                    referenceStorage.addProjectImportedReference(fileId, functionId, fut.completer())
                                }
                            } else {
                                def existingRef = graql.parse(GET_INTERNAL_REFERENCE_BY_FILE_NAME
                                        .replace("<xFileName>", refFile)
                                        .replace("<yFuncDefsId>", osFunc.functionDefinitionsId)).execute() as List<ConceptMap>
                                importReference = existingRef.isEmpty()
                                if (!importReference) {
                                    def fileId = existingRef.get(0).get("file").asEntity().id().toString()
                                    def functionId = existingRef.get(0).get("func").asEntity().id().toString()
                                    def fut = Future.future()
                                    cacheFutures.addAll(fut)
                                    referenceStorage.addProjectImportedReference(fileId, functionId, fut.completer())
                                }
                            }
                        } else {
                            if (isExternal) {
                                def defOsFunc = importData.openSourceFunctions.get(lineData[1])
                                if (defOsFunc == null) {
                                    def fut = Future.future()
                                    cacheFutures.add(fut)
                                    defOsFunc = grakn.getOrCreateOpenSourceFunction(lineData[1], graql, fut.completer())
                                    importData.openSourceFunctions.put(lineData[1], defOsFunc)
                                }

                                def existingRef = graql.parse(GET_EXTERNAL_REFERENCE
                                        .replace("<projectId>", projectId)
                                        .replace("<funcDefsId>", defOsFunc.functionDefinitionsId)
                                        .replace("<funcRefsId>", osFunc.functionReferencesId)).execute() as List<ConceptMap>
                                importReference = existingRef.isEmpty()
                                if (!importReference) {
                                    def function1Id = existingRef.get(0).get("yFunc").asEntity().id().toString()
                                    def function2Id = existingRef.get(0).get("func").asEntity().id().toString()
                                    def fut1 = Future.future()
                                    def fut2 = Future.future()
                                    cacheFutures.addAll(fut1, fut2)
                                    referenceStorage.addProjectImportedFunction(githubRepository, lineData[3], osFunc.functionId, fut1.completer())
                                    referenceStorage.addProjectImportedReference(function1Id, function2Id, fut2.completer())
                                }
                            } else {
                                def refFunctionId = importData.definedFunctions.get(lineData[1])
                                if (refFunctionId == null) {
                                    def existingRef = graql.parse(GET_INTERNAL_REFERENCE_BY_FUNCTION_NAME
                                            .replace("<funcName1>", lineData[1])
                                            .replace("<funcName2>", lineData[3])).execute() as List<ConceptMap>
                                    importReference = existingRef.isEmpty()
                                    if (!importReference) {
                                        def existingFunc1Id = existingRef.get(0).get("func1").asEntity().id().toString()
                                        def existingFunc2Id = existingRef.get(0).get("func2").asEntity().id().toString()
                                        def fut = Future.future()
                                        cacheFutures.addAll(fut)
                                        referenceStorage.addProjectImportedReference(existingFunc1Id, existingFunc2Id, fut.completer())
                                    }
                                } else {
                                    def funcId = importData.definedFunctions.get(lineData[3])
                                    if (funcId == null || importData.definedFunctionInstances.get(refFunctionId) == null
                                            || importData.definedFunctionInstances.get(funcId) == null) {
                                        //println "todo: me4" //todo: me
                                        return
                                    }

                                    def existingRef = graql.parse(GET_INTERNAL_REFERENCE
                                            .replace("<xFunctionInstanceId>", importData.definedFunctionInstances.get(refFunctionId))
                                            .replace("<yFunctionInstanceId>", importData.definedFunctionInstances.get(funcId))).execute() as List<ConceptMap>
                                    importReference = existingRef.isEmpty()
                                    if (!importReference) {
                                        def fut = Future.future()
                                        cacheFutures.addAll(fut)
                                        referenceStorage.addProjectImportedReference(refFunctionId, funcId, fut.completer())
                                    }
                                }
                            }
                        }
                    }

                    if (importReference) {
                        def startOffset = lineData[5]
                        def endOffset = lineData[6]
                        def refFunctionId = importData.definedFunctions.get(lineData[1])
                        def importCode = new ImportableSourceCode()
                        importCode.isFileReferencing = isFileReferencing
                        importCode.isExternalReference = isExternal

                        if (isFileReferencing) {
                            //reference from files
                            def fileId = importData.importedFiles.get(lineData[1])
                            if (fileId == null) {
                                //println "todo: me2" //todo: me
                                return
                            }

                            if (isExternal) {
                                //internal file references external function
                                def fut = Future.future()
                                importFutures.add(fut)
                                importCode.fileId = fileId
                                importCode.fileQualifiedName = lineData[2]
                                importCode.filename = lineData[1]
                                importCode.fileLocation = lineData[0]
                                importCode.referenceFunctionId = osFunc.functionId
                                importCode.insertQuery = graql.parse(IMPORT_EXTERNAL_REFERENCED_FUNCTION_BY_FILE
                                        .replace("<xFileId>", importCode.fileId)
                                        .replace("<projectId>", projectId)
                                        .replace("<funcRefsId>", osFunc.functionReferencesId)
                                        .replace("<createDate>", Instant.now().toString())
                                        .replace("<qualifiedName>", lineData[4])
                                        .replace("<commitSha1>", commitSha1)
                                        .replace("<commitDate>", commitDate)
                                        .replace("<startOffset>", startOffset)
                                        .replace("<endOffset>", endOffset)
                                        .replace("<isJdk>", lineData[8]))
                                fut.complete(importCode)
                            } else {
                                //internal file references internal function
                                def funcId = importData.definedFunctions.get(lineData[3])
                                if (funcId == null || importData.definedFunctionInstances.get(funcId) == null) {
                                    //println "todo: me3" //todo: me
                                    return
                                }

                                def fut = Future.future()
                                importFutures.add(fut)
                                importCode.fileId = fileId
                                importCode.fileQualifiedName = lineData[2]
                                importCode.fileLocation = lineData[0]
                                importCode.referenceFunctionId = funcId
                                importCode.referenceFunctionInstanceId = importData.definedFunctionInstances.get(funcId)
                                importCode.insertQuery = graql.parse(IMPORT_FILE_TO_FUNCTION_REFERENCE
                                        .replace("<xFileId>", importCode.fileId)
                                        .replace("<yFuncInstanceId>", importCode.referenceFunctionInstanceId)
                                        .replace("<createDate>", Instant.now().toString())
                                        .replace("<startOffset>", startOffset)
                                        .replace("<endOffset>", endOffset)
                                        .replace("<isJdk>", lineData[8]))
                                fut.complete(importCode)
                            }
                        } else {
                            //references from functions
                            if (isExternal) {
                                if (refFunctionId == null || osFunc.functionId == null ||
                                        importData.definedFunctionInstances.get(refFunctionId) == null) {
                                    //println "todo: me5" //todo: me
                                    return
                                }

                                //internal function references external function
                                def fut = Future.future()
                                importFutures.add(fut)
                                importCode.fileLocation = lineData[0]
                                importCode.functionId = refFunctionId
                                importCode.functionQualifiedName = lineData[2]
                                importCode.functionInstanceId = importData.definedFunctionInstances.get(refFunctionId)
                                importCode.referenceFunctionId = osFunc.functionId
                                importCode.referenceFunctionName = lineData[3]
                                importCode.insertQuery = graql.parse(IMPORT_EXTERNAL_REFERENCED_FUNCTIONS
                                        .replace("<xFuncInstanceId>", importCode.functionInstanceId)
                                        .replace("<projectId>", projectId)
                                        .replace("<funcRefsId>", osFunc.functionReferencesId)
                                        .replace("<createDate>", Instant.now().toString())
                                        .replace("<qualifiedName>", lineData[4])
                                        .replace("<commitSha1>", commitSha1)
                                        .replace("<commitDate>", commitDate)
                                        .replace("<startOffset>", startOffset)
                                        .replace("<endOffset>", endOffset)
                                        .replace("<isJdk>", lineData[8]))
                                fut.complete(importCode)
                            } else {
                                //internal function references internal function
                                def funcId = importData.definedFunctions.get(lineData[3])
                                if (funcId == null) {
                                    //println "todo: me" //todo: me
                                    return
                                }

                                def fut = Future.future()
                                importFutures.add(fut)
                                importCode.fileLocation = lineData[0]
                                importCode.functionId = refFunctionId
                                importCode.functionQualifiedName = lineData[2]
                                importCode.functionInstanceId = importData.definedFunctionInstances.get(refFunctionId)
                                importCode.referenceFunctionId = funcId
                                importCode.referenceFunctionInstanceId = importData.definedFunctionInstances.get(funcId)
                                importCode.insertQuery = graql.parse(IMPORT_INTERNAL_REFERENCED_FUNCTIONS
                                        .replace("<xFuncInstanceId>", importCode.functionInstanceId)
                                        .replace("<yFuncInstanceId>", importCode.referenceFunctionInstanceId)
                                        .replace("<createDate>", Instant.now().toString())
                                        .replace("<startOffset>", startOffset)
                                        .replace("<endOffset>", endOffset)
                                        .replace("<isJdk>", lineData[8]))
                                fut.complete(importCode)
                            }
                        }
                        WebLauncher.metrics.counter("ImportReferencedFunction").inc()
                    }
                }
            }
            tx.commit()
        } catch (all) {
            handler.handle(Future.failedFuture(all))
            return
        } finally {
            tx.close()
        }

        CompositeFuture.all(importFutures).setHandler({
            if (it.failed()) {
                handler.handle(Future.failedFuture(it.cause()))
            } else {
                log.debug "Executing insert queries"
                tx = graknSession.transaction(GraknTxType.BATCH)
                try {
                    def futures = it.result().list() as List<ImportableSourceCode>
                    for (def importCode : futures) {
                        importCode.insertQuery.withTx(tx).execute() as List<ConceptMap>

                        if (importCode.isFileReferencing) {
                            if (importCode.isExternalReference) {
                                //cache imported reference (internal file -> external function)
                                def fut1 = Future.future()
                                cacheFutures.add(fut1)
                                referenceStorage.addProjectImportedReference(importCode.fileId, importCode.referenceFunctionId, fut1.completer())

                                //add external file reference
                                def file = new JsonObject()
                                        .put("qualified_name", importCode.fileQualifiedName)
                                        .put("file_location", importCode.fileLocation)
                                        .put("commit_sha1", commitSha1)
                                        .put("id", importCode.fileId)
                                        .put("short_class_name", importCode.fileQualifiedName)
                                        .put("github_repository", githubRepository)
                                        .put("is_file", true)
                                def fut2 = Future.future()
                                cacheFutures.add(fut2)
                                referenceStorage.addFunctionReference(importCode.referenceFunctionId, file, fut2.completer())
                            } else {
                                //cache imported reference (internal file -> internal function)
                                def fut = Future.future()
                                cacheFutures.add(fut)
                                referenceStorage.addProjectImportedReference(importCode.fileId, importCode.referenceFunctionId, fut.completer())
                            }
                        } else {
                            if (importCode.isExternalReference) {
                                //cache imported function/reference (internal function -> external function)
                                def fut1 = Future.future()
                                cacheFutures.add(fut1)
                                referenceStorage.addProjectImportedFunction(githubRepository, importCode.referenceFunctionName,
                                        importCode.referenceFunctionId, fut1.completer())

                                def fut2 = Future.future()
                                cacheFutures.add(fut2)
                                referenceStorage.addProjectImportedReference(importCode.functionId, importCode.referenceFunctionId, fut2.completer())

                                //add external function reference
                                def function = new JsonObject()
                                        .put("qualified_name", importCode.functionQualifiedName)
                                        .put("file_location", importCode.fileLocation)
                                        .put("commit_sha1", commitSha1)
                                        .put("id", importCode.functionId)
                                        .put("short_class_name", getShortQualifiedClassName(importCode.functionQualifiedName))
                                        .put("class_name", getQualifiedClassName(importCode.functionQualifiedName))
                                        .put("short_method_signature", getShortMethodSignature(importCode.functionQualifiedName))
                                        .put("method_signature", getMethodSignature(importCode.functionQualifiedName))
                                        .put("github_repository", githubRepository)
                                        .put("is_function", true)
                                def fut3 = Future.future()
                                cacheFutures.add(fut3)
                                referenceStorage.addFunctionReference(importCode.referenceFunctionId, function, fut3.completer())
                            } else {
                                //cache imported reference (internal function -> internal function)
                                def fut = Future.future()
                                cacheFutures.add(fut)
                                referenceStorage.addProjectImportedReference(importCode.functionId, importCode.referenceFunctionId, fut.completer())
                            }
                        }
                    }
                    tx.commit()
                } catch (all) {
                    handler.handle(Future.failedFuture(all))
                    return
                } finally {
                    tx.close()
                }
                logPrintln(job, "Importing function references took: " + asPrettyTime(importReferencesContext.stop()))
                handler.handle(Future.succeededFuture())
            }
        })
    }

    private void finalizeImport(Job job, String githubRepository, Handler<AsyncResult> handler) {
        redis.getProjectFirstIndexed(githubRepository, {
            if (it.failed()) {
                handler.handle(Future.failedFuture(it.cause()))
                return
            }

            def futures = new ArrayList<Future>()
            if (it.result() == null) {
                def fut = Future.future()
                futures.add(fut)
                redis.setProjectFirstIndexed(githubRepository, Instant.now(), fut.completer())
            }

            def fut2 = Future.future()
            futures.add(fut2)
            redis.setProjectLastIndexed(githubRepository, Instant.now(), fut2.completer())
            def fut3 = Future.future()
            futures.add(fut3)
            redis.setProjectLastIndexedCommitInformation(githubRepository,
                    job.data.getString("commit"), job.data.getInstant("commit_date"), fut3.completer())

            CompositeFuture.all(futures).setHandler({
                if (it.failed()) {
                    handler.handle(Future.failedFuture(it.cause()))
                } else {
                    logPrintln(job, "Finished importing project")
                    handler.handle(Future.succeededFuture())
                }
            })
        })
    }

    private File downloadAndExtractImportFiles(Job job) {
        logPrintln(job, "Staging index results")
        def indexZipUuid = job.data.getString("import_index_file_id")
        def remoteIndexResultsZip = new File(uploadsDirectory, indexZipUuid + ".zip")
        def indexResultsZip = new File(uploadsDirectory, indexZipUuid + ".zip")
        if (config().getString("uploads.directory.local") != null) {
            indexResultsZip = new File(config().getString("uploads.directory.local"), indexZipUuid + ".zip")
        }
        indexResultsZip.parentFile.mkdirs()

        String uploadsHost = config().getString("uploads.host")
        if (uploadsHost != null) {
            log.info "Connecting to remote SFTP server"
            String uploadsUsername = config().getString("uploads.username")
            String uploadsPassword = config().getString("uploads.password")
            def remoteSFTPSession = new JSch().getSession(uploadsUsername, uploadsHost, 22)
            remoteSFTPSession.setConfig("StrictHostKeyChecking", "no")
            remoteSFTPSession.setPassword(uploadsPassword)
            remoteSFTPSession.connect()
            log.info "Connected to remote SFTP server"

            try {
                log.debug "Downloading index results from SFTP"
                //try to connect 3 times (todo: use retryer?)
                def exception = null
                boolean connected = false
                def sftpChannel = null
                for (int i = 0; i < 3; i++) {
                    try {
                        def channel = remoteSFTPSession.openChannel("sftp")
                        channel.connect()
                        sftpChannel = (ChannelSftp) channel
                        connected = true
                    } catch (JSchException ex) {
                        Thread.sleep(1000)
                        exception = ex
                    }
                    if (connected) break
                }
                if (connected) {
                    sftpChannel.get(remoteIndexResultsZip.absolutePath, indexResultsZip.absolutePath)
                    //todo: delete remote zip
                    sftpChannel.exit()
                } else if (exception != null) {
                    throw exception
                }
            } finally {
                remoteSFTPSession.disconnect()
            }
        }

        def outputDirectory = new File(job.data.getString("output_directory"))
        outputDirectory.deleteDir()
        outputDirectory.mkdirs()

        def zipFile = new ZipFile(indexResultsZip)
        zipFile.entries().each { zipEntry ->
            def path = Paths.get(outputDirectory.absolutePath + File.separatorChar + zipEntry.name)
            if (zipEntry.directory) {
                Files.createDirectories(path)
            } else {
                def parentDir = path.getParent()
                if (!Files.exists(parentDir)) {
                    Files.createDirectories(parentDir)
                }
                Files.copy(zipFile.getInputStream(zipEntry), path)
            }
        }
        return outputDirectory
    }

}
