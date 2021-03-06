package io.gitdetective.indexer.stage

import io.gitdetective.web.dao.storage.ReferenceStorage
import io.vertx.blueprint.kue.queue.Job
import io.vertx.core.*
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.FileProps
import io.vertx.core.file.OpenOptions
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static io.gitdetective.indexer.IndexerServices.logPrintln

/**
 * Filters out definition/reference data which has already been imported
 *
 * @author <a href="mailto:brandon.fergerson@codebrig.com">Brandon Fergerson</a>
 */
class GitDetectiveImportFilter extends AbstractVerticle {

    public static final String GITDETECTIVE_IMPORT_FILTER = "GitDetectiveImportFilter"
    private final static Logger log = LoggerFactory.getLogger(GitDetectiveImportFilter.class)
    private final ReferenceStorage referenceStorage

    GitDetectiveImportFilter(ReferenceStorage referenceStorage) {
        this.referenceStorage = referenceStorage
    }

    @Override
    void start() throws Exception {
        vertx.eventBus().consumer(GITDETECTIVE_IMPORT_FILTER, {
            def job = (Job) it.body()
            doFilter(job, {
                if (it.failed()) {
                    it.cause().printStackTrace()
                    logPrintln(job, it.cause().getMessage())
                    job.done(it.cause())
                } else {
                    sendToImporter(job)
                }
            })
        })
        log.info "GitDetectiveImportFilter started"
    }

    private void doFilter(Job job, Handler<AsyncResult> handler) {
        def githubRepository = job.data.getString("github_repository")
        def outputDirectory = job.data.getString("output_directory")
        def skipFilter = job.data.getBoolean("skip_filter", false)
        if (skipFilter) {
            def filesOutput = new File(outputDirectory, "files_raw.txt")
            def filesOutputFinal = new File(outputDirectory, "files.txt")
            filesOutput.renameTo(filesOutputFinal)
            def readyFunctionReferences = new File(outputDirectory, "functions_reference_ready.txt")
            def functionReferencesFinal = new File(outputDirectory, "functions_reference.txt")
            readyFunctionReferences.renameTo(functionReferencesFinal)
            def readyFunctionDefinitions = new File(outputDirectory, "functions_definition_ready.txt")
            def functionDefinitionsFinal = new File(outputDirectory, "functions_definition.txt")
            readyFunctionDefinitions.renameTo(functionDefinitionsFinal)

            //finish without any filtering
            handler.handle(Future.succeededFuture())
            return
        }

        if (referenceStorage.batchSupported) {
            batchFilterFiles(job, outputDirectory, {
                if (it.failed()) {
                    handler.handle(Future.failedFuture(it.cause()))
                } else {
                    batchFilterDefinitions(job, outputDirectory, {
                        if (it.failed()) {
                            handler.handle(Future.failedFuture(it.cause()))
                        } else {
                            batchFilterReferences(job, outputDirectory, handler)
                        }
                    })
                }
            })
        } else {
            filterFiles(githubRepository, job, outputDirectory, {
                if (it.failed()) {
                    handler.handle(Future.failedFuture(it.cause()))
                } else {
                    filterDefinitions(githubRepository, job, outputDirectory, {
                        if (it.failed()) {
                            handler.handle(Future.failedFuture(it.cause()))
                        } else {
                            filterReferences(githubRepository, job, outputDirectory, handler)
                        }
                    })
                }
            })
        }
    }

    private void batchFilterReferences(Job job, String outputDirectory, Handler<AsyncResult> handler) {
        logPrintln(job, "Filtering already imported references")
        def readyFunctionReferences = new File(outputDirectory, "functions_reference_ready.txt")
        def functionReferencesFinal = new File(outputDirectory, "functions_reference.txt")
        functionReferencesFinal.createNewFile()

        Set<PosixFilePermission> perms = new HashSet<>()
        perms.add(PosixFilePermission.OTHERS_READ)
        perms.add(PosixFilePermission.OTHERS_WRITE)
        Files.setPosixFilePermissions(readyFunctionReferences.toPath(), perms)
        Files.setPosixFilePermissions(functionReferencesFinal.toPath(), perms)
        referenceStorage.batchImportProjectReferences(readyFunctionReferences, functionReferencesFinal, handler)
    }

    private void filterReferences(String githubRepository, Job job, String outputDirectory,
                                  Handler<AsyncResult> handler) {
        logPrintln(job, "Filtering already imported references")
        def futures = new ArrayList<Future>()
        def lineNumber = 0
        def readyFunctionReferences = new File(outputDirectory, "functions_reference_ready.txt")
        def functionReferencesFinal = new File(outputDirectory, "functions_reference.txt")
        readyFunctionReferences.eachLine { line ->
            lineNumber++
            if (lineNumber > 1) {
                def lineData = line.split("\\|")

                def fileOrFuncFut = Future.future()
                if (lineData[1].contains("#")) {
                    referenceStorage.getProjectFunctionId(githubRepository, lineData[1], fileOrFuncFut.completer())
                } else {
                    referenceStorage.getProjectFileId(githubRepository, lineData[1], fileOrFuncFut.completer())
                }
                def funcFut = Future.future()
                referenceStorage.getProjectFunctionId(githubRepository, lineData[3], funcFut.completer())

                def fut = Future.future()
                futures.add(fut)
                CompositeFuture.all(fileOrFuncFut, funcFut).setHandler({
                    if (it.failed()) {
                        it.cause().printStackTrace()
                        fut.fail(it.cause())
                    } else {
                        def results = it.result().list()
                        def existingFileOrFunction = results.get(0) as Optional<String>
                        def existingFunction = results.get(1) as Optional<String>

                        if (existingFileOrFunction.isPresent() && existingFunction.isPresent()) {
                            //check if import needed
                            referenceStorage.projectHasReference(existingFileOrFunction.get(), existingFunction.get(), {
                                if (it.failed()) {
                                    it.cause().printStackTrace()
                                    fut.fail(it.cause())
                                } else {
                                    if (!it.result()) {
                                        functionReferencesFinal.append("$line\n") //do import
                                    }
                                    fut.complete()
                                }
                            })
                        } else {
                            functionReferencesFinal.append("$line\n") //do import
                            fut.complete()
                        }
                    }
                })
            } else {
                functionReferencesFinal.append("$line\n") //header
            }
        }
        CompositeFuture.all(futures).setHandler(handler)
    }

    private void batchFilterDefinitions(Job job, String outputDirectory, Handler<AsyncResult> handler) {
        logPrintln(job, "Filtering already imported definitions")
        def readyFunctionDefinitions = new File(outputDirectory, "functions_definition_ready.txt")
        def functionDefinitionsFinal = new File(outputDirectory, "functions_definition.txt")
        functionDefinitionsFinal.createNewFile()

        Set<PosixFilePermission> perms = new HashSet<>()
        perms.add(PosixFilePermission.OTHERS_READ)
        perms.add(PosixFilePermission.OTHERS_WRITE)
        Files.setPosixFilePermissions(readyFunctionDefinitions.toPath(), perms)
        Files.setPosixFilePermissions(functionDefinitionsFinal.toPath(), perms)
        referenceStorage.batchImportProjectDefinitions(readyFunctionDefinitions, functionDefinitionsFinal, handler)
    }

    private void filterDefinitions(String githubRepository, Job job, String outputDirectory,
                                   Handler<AsyncResult> handler) {
        logPrintln(job, "Filtering already imported definitions")
        def futures = new ArrayList<Future>()
        def lineNumber = 0
        def readyFunctionDefinitions = new File(outputDirectory, "functions_definition_ready.txt")
        def functionDefinitionsFinal = new File(outputDirectory, "functions_definition.txt")
        readyFunctionDefinitions.eachLine { line ->
            lineNumber++
            if (lineNumber > 1) {
                def lineData = line.split("\\|")

                def fileFut = Future.future()
                referenceStorage.getProjectFileId(githubRepository, lineData[0], fileFut.completer())
                def funcFut = Future.future()
                referenceStorage.getProjectFunctionId(githubRepository, lineData[1], funcFut.completer())

                def fut = Future.future()
                futures.add(fut)
                CompositeFuture.all(fileFut, funcFut).setHandler({
                    if (it.failed()) {
                        it.cause().printStackTrace()
                        fut.fail(it.cause())
                    } else {
                        def results = it.result().list()
                        def existingFile = results.get(0) as Optional<String>
                        def existingFunction = results.get(1) as Optional<String>

                        if (existingFile.isPresent() && existingFunction.isPresent()) {
                            //check if import needed
                            referenceStorage.projectHasDefinition(existingFile.get(), existingFunction.get(), {
                                if (it.failed()) {
                                    it.cause().printStackTrace()
                                    fut.fail(it.cause())
                                } else {
                                    if (!it.result()) {
                                        functionDefinitionsFinal.append("$line\n") //do import
                                    }
                                    fut.complete()
                                }
                            })
                        } else {
                            functionDefinitionsFinal.append("$line\n") //do import
                            fut.complete()
                        }
                    }
                })
            } else {
                functionDefinitionsFinal.append("$line\n") //header
            }
        }
        CompositeFuture.all(futures).setHandler(handler)
    }

    private void batchFilterFiles(Job job, String outputDirectory, Handler<AsyncResult> handler) {
        logPrintln(job, "Filtering already imported files")
        def filesOutput = new File(outputDirectory, "files_raw.txt")
        def filesOutputFinal = new File(outputDirectory, "files.txt")
        filesOutputFinal.createNewFile()

        Set<PosixFilePermission> perms = new HashSet<>()
        perms.add(PosixFilePermission.OTHERS_READ)
        perms.add(PosixFilePermission.OTHERS_WRITE)
        Files.setPosixFilePermissions(filesOutput.toPath(), perms)
        Files.setPosixFilePermissions(filesOutputFinal.toPath(), perms)
        referenceStorage.batchImportProjectFiles(filesOutput, filesOutputFinal, handler)
    }

    private void filterFiles(String githubRepository, Job job, String outputDirectory, Handler<AsyncResult> handler) {
        logPrintln(job, "Filtering already imported files")
        def futures = new ArrayList<Future>()
        def lineNumber = 0
        def filesOutput = new File(outputDirectory, "files_raw.txt")
        def filesOutputFinal = new File(outputDirectory, "files.txt")
        filesOutput.eachLine { line ->
            lineNumber++
            if (lineNumber > 1) {
                def lineData = line.split("\\|")

                def fut = Future.future()
                futures.add(fut)
                referenceStorage.getProjectFileId(githubRepository, lineData[1], {
                    if (it.failed()) {
                        it.cause().printStackTrace()
                        fut.fail(it.cause())
                    } else {
                        if (!it.result().isPresent()) {
                            filesOutputFinal.append("$line\n") //do import
                        }
                        fut.complete()
                    }
                })
            } else {
                filesOutputFinal.append("$line\n") //header
            }
        }
        CompositeFuture.all(futures).setHandler(handler)
    }

    private void sendToImporter(Job job) {
        def outputDirectory = job.data.getString("output_directory")
        def results = new File(outputDirectory, "gitdetective_index_results.zip")
        def filesOutput = new File(outputDirectory, "files.txt")
        def osFunctionsOutput = new File(outputDirectory, "functions_open-source.txt")
        def functionDefinitions = new File(outputDirectory, "functions_definition.txt")
        def functionReferences = new File(outputDirectory, "functions_reference.txt")
        FileOutputStream fos = new FileOutputStream(results.absolutePath)
        ZipOutputStream zos = new ZipOutputStream(fos)
        addToZipFile(filesOutput, zos)
        addToZipFile(osFunctionsOutput, zos)
        addToZipFile(functionDefinitions, zos)
        addToZipFile(functionReferences, zos)
        zos.close()
        fos.close()

        logPrintln(job, "Sending index results to importer")
        def ssl = config().getBoolean("gitdetective_service.ssl_enabled")
        def gitdetectiveHost = config().getString("gitdetective_service.host")
        def gitdetectivePort = config().getInteger("gitdetective_service.port")
        def fs = vertx.fileSystem()
        def clientOptions = new WebClientOptions()
        clientOptions.setVerifyHost(false) //todo: why is this needed now?
        clientOptions.setTrustAll(true)
        def client = WebClient.create(vertx, clientOptions)

        fs.props(results.absolutePath, { ares ->
            FileProps props = ares.result()
            long size = props.size()

            HttpRequest<Buffer> req = client.post(gitdetectivePort, gitdetectiveHost, "/indexes").ssl(ssl)
            req.putHeader("content-length", "" + size)
            fs.open(results.absolutePath, new OpenOptions(), { ares2 ->
                req.sendStream(ares2.result(), { ar ->
                    //clean index results folder
                    new File(job.data.getString("output_directory")).deleteDir()

                    if (ar.succeeded()) {
                        job.data.put("import_index_file_id", ar.result().bodyAsString())
                        client.post(gitdetectivePort, gitdetectiveHost, "/jobs/transfer").ssl(ssl).sendJson(job, {
                            if (it.succeeded()) {
                                job.done()
                            } else {
                                it.cause().printStackTrace()
                                logPrintln(job, "Failed to send project to importer")
                                job.done(it.cause())
                            }
                            client.close()
                        })
                    } else {
                        ar.cause().printStackTrace()
                        logPrintln(job, "Failed to send project to importer")
                        job.done(ar.cause())
                        client.close()
                    }
                })
            })
        })
    }

    private static void addToZipFile(File file, ZipOutputStream zos) throws FileNotFoundException, IOException {
        FileInputStream fis = new FileInputStream(file)
        ZipEntry zipEntry = new ZipEntry(file.getName())
        zos.putNextEntry(zipEntry)

        byte[] bytes = new byte[1024]
        int length
        while ((length = fis.read(bytes)) >= 0) {
            zos.write(bytes, 0, length)
        }
        zos.closeEntry()
        fis.close()
    }

}
