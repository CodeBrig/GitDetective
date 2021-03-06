package io.gitdetective.web

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.Lists
import io.gitdetective.web.dao.JobsDAO
import io.gitdetective.web.dao.RedisDAO
import io.gitdetective.web.dao.storage.ReferenceStorage
import io.gitdetective.web.work.importer.GraknImporter
import io.vertx.core.AbstractVerticle
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.templ.HandlebarsTemplateEngine

import javax.net.ssl.SSLException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit

import static io.gitdetective.web.WebServices.*

/**
 * Serves GitDetective website
 *
 * @author <a href="mailto:brandon.fergerson@codebrig.com">Brandon Fergerson</a>
 */
class GitDetectiveWebsite extends AbstractVerticle {

    private final static Logger log = LoggerFactory.getLogger(GitDetectiveWebsite.class)
    private final static ResourceBundle buildBundle = ResourceBundle.getBundle("gitdetective_build")
    private static volatile long CURRENTLY_INDEXING_COUNT = 0
    private static volatile long CURRENTLY_IMPORTING_COUNT = 0
    private static volatile long TOTAL_COMPUTE_TIME = 0
    private static volatile long TOTAL_PROJECT_COUNT = 0
    private static volatile long TOTAL_FILE_COUNT = 0
    private static volatile long TOTAL_METHOD_COUNT = 0
    private static volatile long TOTAL_DEFINITION_COUNT = 0
    private static volatile long TOTAL_REFERENCE_COUNT = 0
    private final JobsDAO jobs
    private final RedisDAO redis
    private final ReferenceStorage storage
    private final Router router
    private final HandlebarsTemplateEngine engine
    private final Cache<String, Boolean> autoBuildCache = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES).build()

    GitDetectiveWebsite(JobsDAO jobs, RedisDAO redis, ReferenceStorage storage, Router router) {
        this.jobs = jobs
        this.redis = redis
        this.storage = storage
        this.router = router
        this.engine = HandlebarsTemplateEngine.create()
    }

    @Override
    void start() throws Exception {
        //website
        router.route("/static/*").handler(StaticHandler.create()
                .setWebRoot("webroot/static")
                .setCachingEnabled(true))
        router.get("/").handler({ ctx ->
            handleIndexPage(ctx)
        })
        router.get("/projects/leaderboard").handler({ ctx ->
            handleProjectLeaderboardPage(ctx)
        })
        router.get("/functions/leaderboard").handler({ ctx ->
            handleFunctionLeaderboardPage(ctx)
        })
        router.get("/:githubUsername/:githubProject").handler({ ctx ->
            handleProjectPage(ctx)
        })
        router.get("/:githubUsername/:githubProject/").handler({ ctx ->
            handleProjectPage(ctx)
        })
        router.get("/static").handler({ ctx ->
            ctx.response().setStatusCode(404).end()
        })
        router.get("/static/").handler({ ctx ->
            ctx.response().setStatusCode(404).end()
        })
        router.get("/favicon.ico").handler({ ctx ->
            //todo: get favicon
            ctx.response().setStatusCode(404).end()
        })
        router.route().last().handler({
            it.response().putHeader("location", "/")
                    .setStatusCode(302).end()
        })
        router.route().failureHandler({
            if ((it.failure() instanceof IllegalStateException && it.failure().message == "Response is closed")
                    || (it.failure() instanceof SSLException && it.failure().message == "SSLEngine closed already")
                    || it.failure() instanceof ClosedChannelException) {
                //ignore; //todo: why do these happen?
            } else {
                it.failure().printStackTrace()
            }
        })

        //set initial db stats
        updateDatabaseStatistics(true)
        //update every minute
        vertx.setPeriodic(TimeUnit.MINUTES.toMillis(1), {
            updateDatabaseStatistics(false)
            log.info "Updated database statistics"
        })
        log.info "GitDetectiveWebsite started"
    }

    private void updateDatabaseStatistics(boolean initial) {
        jobs.getActiveCount("IndexGithubProject", {
            if (it.succeeded()) {
                CURRENTLY_INDEXING_COUNT = it.result()
            } else {
                it.cause().printStackTrace()
            }
        })
        jobs.getActiveCount(GraknImporter.GRAKN_INDEX_IMPORT_JOB_TYPE, {
            if (it.succeeded()) {
                CURRENTLY_IMPORTING_COUNT = it.result()
            } else {
                it.cause().printStackTrace()
            }
        })

        if (config().getBoolean("grakn.enabled")) {
            if (initial) {
                redis.getComputeTime({
                    WebLauncher.metrics.counter("GraknComputeTime").inc(TOTAL_COMPUTE_TIME = it.result())
                })
                redis.getProjectCount({
                    WebLauncher.metrics.counter("CreateProject").inc(TOTAL_PROJECT_COUNT = it.result())
                })
                redis.getFileCount({
                    WebLauncher.metrics.counter("ImportFile").inc(TOTAL_FILE_COUNT = it.result())
                })
                redis.getMethodCount({
                    WebLauncher.metrics.counter("ImportMethod").inc(TOTAL_METHOD_COUNT = it.result())
                })
                redis.getDefinitionCount({
                    WebLauncher.metrics.counter("ImportDefinedFunction").inc(TOTAL_DEFINITION_COUNT = it.result())
                })
                redis.getReferenceCount({
                    WebLauncher.metrics.counter("ImportReferencedFunction").inc(TOTAL_REFERENCE_COUNT = it.result())
                })
            } else {
                redis.cacheComputeTime(TOTAL_COMPUTE_TIME = WebLauncher.metrics.counter("GraknComputeTime").getCount())
                redis.cacheProjectCount(TOTAL_PROJECT_COUNT = WebLauncher.metrics.counter("CreateProject").getCount())
                redis.cacheFileCount(TOTAL_FILE_COUNT = WebLauncher.metrics.counter("ImportFile").getCount())
                redis.cacheMethodCount(TOTAL_METHOD_COUNT = WebLauncher.metrics.counter("ImportMethod").getCount())
                redis.cacheDefinitionCount(TOTAL_DEFINITION_COUNT = WebLauncher.metrics.counter("ImportDefinedFunction").getCount())
                redis.cacheReferenceCount(TOTAL_REFERENCE_COUNT = WebLauncher.metrics.counter("ImportReferencedFunction").getCount())
            }
        } else {
            redis.getComputeTime({ TOTAL_COMPUTE_TIME = it.result() })
            redis.getProjectCount({ TOTAL_PROJECT_COUNT = it.result() })
            redis.getFileCount({ TOTAL_FILE_COUNT = it.result() })
            redis.getMethodCount({ TOTAL_METHOD_COUNT = it.result() })
            redis.getDefinitionCount({ TOTAL_DEFINITION_COUNT = it.result() })
            redis.getReferenceCount({ TOTAL_REFERENCE_COUNT = it.result() })
        }
    }

    private void handleIndexPage(RoutingContext ctx) {
        ctx.put("gitdetective_url", config().getString("gitdetective_url"))
        ctx.put("gitdetective_static_url", config().getString("gitdetective_static_url"))
        ctx.put("gitdetective_eventbus_url", config().getString("gitdetective_url") + "backend/services/eventbus")
        ctx.put("gitdetective_version", buildBundle.getString("version"))

        //load and send page data
        log.debug "Loading index page"
        CompositeFuture.all(Lists.asList(
                getActiveJobs(ctx),
                getProjectReferenceLeaderboard(ctx, 5),
                getFunctionReferenceLeaderboard(ctx, 5),
                getDatabaseStatistics(ctx)
        )).setHandler({
            log.debug "Rendering index page"
            engine.render(ctx, "webroot", "/index.hbs", { res ->
                if (res.succeeded()) {
                    log.info "Displaying index page"
                    ctx.response().end(res.result())
                } else {
                    ctx.fail(res.cause())
                }
            })
        })
    }

    private void handleProjectLeaderboardPage(RoutingContext ctx) {
        ctx.put("gitdetective_url", config().getString("gitdetective_url"))
        ctx.put("gitdetective_static_url", config().getString("gitdetective_static_url"))
        ctx.put("gitdetective_eventbus_url", config().getString("gitdetective_url") + "backend/services/eventbus")
        ctx.put("gitdetective_version", buildBundle.getString("version"))

        //load and send page data
        log.debug "Loading project leaderboard page"
        getProjectReferenceLeaderboard(ctx, 100).setHandler({
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                log.debug "Rendering project leaderboard page"
                engine.render(ctx, "webroot", "/project_leaderboard.hbs", { res ->
                    if (res.succeeded()) {
                        log.info "Displaying project leaderboard page"
                        ctx.response().end(res.result())
                    } else {
                        ctx.fail(res.cause())
                    }
                })
            }
        })
    }

    private void handleFunctionLeaderboardPage(RoutingContext ctx) {
        ctx.put("gitdetective_url", config().getString("gitdetective_url"))
        ctx.put("gitdetective_static_url", config().getString("gitdetective_static_url"))
        ctx.put("gitdetective_eventbus_url", config().getString("gitdetective_url") + "backend/services/eventbus")
        ctx.put("gitdetective_version", buildBundle.getString("version"))

        //load and send page data
        log.debug "Loading function leaderboard page"
        getFunctionReferenceLeaderboard(ctx, 100).setHandler({
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                log.debug "Rendering function leaderboard page"
                engine.render(ctx, "webroot", "/function_leaderboard.hbs", { res ->
                    if (res.succeeded()) {
                        log.info "Displaying function leaderboard page"
                        ctx.response().end(res.result())
                    } else {
                        ctx.fail(res.cause())
                    }
                })
            }
        })
    }

    private Future getActiveJobs(RoutingContext ctx) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_ACTIVE_JOBS, new JsonObject(), {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                def activeJobs = it.result().body() as JsonArray
                //only most recent 10
                if (activeJobs.size() > 10) {
                    activeJobs = new JsonArray(activeJobs.take(10))
                }
                //add pretty job type
                for (int i = 0; i < activeJobs.size(); i++) {
                    def job = activeJobs.getJsonObject(i)
                    if (job.getString("type") == GraknImporter.GRAKN_INDEX_IMPORT_JOB_TYPE) {
                        job.getJsonObject("data").put("job_type", "Importing")
                    } else {
                        job.getJsonObject("data").put("job_type", "Indexing")
                    }
                }

                ctx.put("active_jobs", activeJobs)
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectReferenceLeaderboard(RoutingContext ctx, int topCount) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_REFERENCE_LEADERBOARD, new JsonObject().put("top_count", topCount), {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                def referenceLeaderboard = it.result().body() as JsonArray

                //make counts pretty
                for (int i = 0; i < referenceLeaderboard.size(); i++) {
                    def project = referenceLeaderboard.getJsonObject(i)
                    def count = project.getString("value") as int
                    project.put("value", asPrettyNumber(count))
                }
                ctx.put("project_reference_leaderboard", referenceLeaderboard)
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getFunctionReferenceLeaderboard(RoutingContext ctx, int topCount) {
        def future = Future.future()
        def handler = future.completer()
        redis.getCachedFunctionLeaderboard({
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                def referenceLeaderboard = it.result().take(topCount) as JsonArray

                //make counts pretty
                for (int i = 0; i < referenceLeaderboard.size(); i++) {
                    def function = referenceLeaderboard.getJsonObject(i)
                    function.put("short_qualified_name", getShortQualifiedMethodName(function.getString("qualified_name")))
                    function.put("external_reference_count", asPrettyNumber(
                            function.getLong("external_reference_count")))
                }
                ctx.put("function_reference_leaderboard", referenceLeaderboard)
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private void handleProjectPage(RoutingContext ctx) {
        def username = ctx.pathParam("githubUsername")
        def project = ctx.pathParam("githubProject")
        def githubRepository = "$username/$project"

        if (!isValidGithubString(username) || !isValidGithubString(project)) {
            //invalid github username/project
            ctx.response().putHeader("location", "/")
                    .setStatusCode(302).end()
            return
        } else {
            ctx.put("github_username", username)
            ctx.put("github_project", project)
            ctx.put("github_repository", githubRepository)
        }
        ctx.put("gitdetective_url", config().getString("gitdetective_url"))
        ctx.put("gitdetective_static_url", config().getString("gitdetective_static_url"))
        ctx.put("gitdetective_eventbus_url", config().getString("gitdetective_url") + "backend/services/eventbus")
        ctx.put("gitdetective_version", buildBundle.getString("version"))

        //load and send page data
        log.debug "Loading project page: $username/$project"
        def repo = new JsonObject().put("github_repository", "$username/$project")
        CompositeFuture.all(Lists.asList(
                getLatestBuildLog(ctx, repo),
                getProjectFileCount(ctx, repo),
                getProjectMethodVersionCount(ctx, repo),
                getProjectFirstIndexed(ctx, repo),
                getProjectLastIndexed(ctx, repo),
                getProjectLastIndexedCommitInformation(ctx, repo),
                getProjectMostReferencedFunctions(ctx, repo)
        )).setHandler({
            log.debug "Rendering project page: $username/$project"
            engine.render(ctx, "webroot", "/project.hbs", { res ->
                if (res.succeeded()) {
                    log.info "Displaying project page: $username/$project"
                    ctx.response().end(res.result())
                } else {
                    ctx.fail(res.cause())
                }
            })
        })

        if (config().getBoolean("auto_build_enabled")) {
            //schedule build/recalculate if can
            def autoBuilt = autoBuildCache.getIfPresent(githubRepository)
            if (autoBuilt == null) {
                log.debug "Checking repository: $githubRepository"
                autoBuildCache.put(githubRepository, true)

                vertx.eventBus().send(GET_TRIGGER_INFORMATION, repo, {
                    def triggerInformation = it.result().body() as JsonObject
                    if (triggerInformation.getBoolean("can_build")) {
                        log.info "Auto-building: " + repo.getString("github_repository")
                        vertx.eventBus().send(CREATE_JOB, repo)
                    }
                })
            }
        }
    }

    private Future getLatestBuildLog(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_LATEST_JOB_LOG, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                def jobLog = it.result().body() as JsonObject
                ctx.put("latest_job_log", jobLog.getJsonArray("logs"))
                ctx.put("latest_job_log_id", jobLog.getLong("job_id"))
                ctx.put("latest_job_log_position", jobLog.getJsonArray("logs").size() - 1)
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectFileCount(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_FILE_COUNT, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                ctx.put("project_file_count", it.result().body())
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectMethodVersionCount(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_METHOD_INSTANCE_COUNT, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                ctx.put("project_method_version_count", it.result().body())
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectMostReferencedFunctions(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_MOST_REFERENCED_FUNCTIONS, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                ctx.put("project_most_referenced_methods", it.result().body())
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectFirstIndexed(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_FIRST_INDEXED, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                ctx.put("project_first_indexed", it.result().body())
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectLastIndexed(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_LAST_INDEXED, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                ctx.put("project_last_indexed", it.result().body())
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private Future getProjectLastIndexedCommitInformation(RoutingContext ctx, JsonObject githubRepository) {
        def future = Future.future()
        def handler = future.completer()
        vertx.eventBus().send(GET_PROJECT_LAST_INDEXED_COMMIT_INFORMATION, githubRepository, {
            if (it.failed()) {
                ctx.fail(it.cause())
            } else {
                def commitInformation = it.result().body() as JsonObject
                if (commitInformation != null) {
                    commitInformation.put("commit_short", commitInformation.getString("commit").substring(0, 7))
                    ctx.put("project_last_indexed_commit_information", commitInformation)
                }
            }
            handler.handle(Future.succeededFuture())
        })
        return future
    }

    private static Future getDatabaseStatistics(RoutingContext ctx) {
        def stats = new JsonArray()
        stats.add(new JsonObject().put("stat1", "Active backlog").put("value1",
                asPrettyNumber(CURRENTLY_INDEXING_COUNT + CURRENTLY_IMPORTING_COUNT))
                .put("stat2", "Projects").put("value2", asPrettyNumber(TOTAL_PROJECT_COUNT)))
        stats.add(new JsonObject().put("stat1", "Definitions").put("value1", asPrettyNumber(TOTAL_DEFINITION_COUNT))
                .put("stat2", "Files").put("value2", asPrettyNumber(TOTAL_FILE_COUNT)))
        stats.add(new JsonObject().put("stat1", "References").put("value1", asPrettyNumber(TOTAL_REFERENCE_COUNT))
                .put("stat2", "Methods").put("value2", asPrettyNumber(TOTAL_METHOD_COUNT)))
        ctx.put("database_statistics", stats)

        def future = Future.future()
        def handler = future.completer()
        handler.handle(Future.succeededFuture())
        return future
    }

}
