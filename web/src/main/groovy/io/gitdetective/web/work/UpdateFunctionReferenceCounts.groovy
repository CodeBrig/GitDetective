package io.gitdetective.web.work

import grakn.client.Grakn
import grakn.client.rpc.RPCSession
import graql.lang.Graql
import groovy.util.logging.Slf4j
import io.gitdetective.web.WebLauncher
import io.gitdetective.web.dao.PostgresDAO
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowStream
import io.vertx.sqlclient.Transaction
import io.vertx.sqlclient.impl.ArrayTuple

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static graql.lang.Graql.*

@Slf4j
class UpdateFunctionReferenceCounts extends AbstractVerticle {

    public static final String PERFORM_TASK_NOW = "UpdateFunctionReferenceCounts"
    private static final String DROP_FUNCTION_REFERENCE_COUNT_TABLE =
            'DROP TABLE IF EXISTS function_reference_count'
    private static final String CREATE_FUNCTION_REFERENCE_COUNT_TABLE =
            'CREATE TABLE function_reference_count AS\n' +
                    'SELECT callee_function_id, SUM(case when deletion = false then 1 else -1 end)\n' +
                    'FROM function_reference\n' +
                    'GROUP BY callee_function_id'

    private final PostgresDAO postgres
    private final RPCSession.Core graknSession

    UpdateFunctionReferenceCounts(PostgresDAO postgres, RPCSession.Core graknSession) {
        this.postgres = postgres
        this.graknSession = graknSession
    }

    @Override
    void start() throws Exception {
        def timer = WebLauncher.metrics.timer(PERFORM_TASK_NOW)
        vertx.eventBus().consumer(PERFORM_TASK_NOW, request -> {
            def time = timer.time()
            log.info(PERFORM_TASK_NOW + " started")

            createFunctionReferenceCountTable({
                if (it.succeeded()) {
                    request.reply(true)
                } else {
                    request.fail(500, it.cause().message)
                }

                log.info(PERFORM_TASK_NOW + " finished")
                time.close()
            })
        })

        //perform every 30 minutes
        vertx.setPeriodic(TimeUnit.MINUTES.toMillis(30), {
            vertx.eventBus().send(PERFORM_TASK_NOW, true)
        })
        vertx.eventBus().send(PERFORM_TASK_NOW, true) //perform on boot
    }

    void createFunctionReferenceCountTable(Handler<AsyncResult<Void>> handler) {
        postgres.client.query(DROP_FUNCTION_REFERENCE_COUNT_TABLE).execute({
            if (it.succeeded()) {
                postgres.client.query(CREATE_FUNCTION_REFERENCE_COUNT_TABLE).execute({
                    if (it.succeeded()) {
                        updateGraknReferenceCounts(handler)
                    } else {
                        handler.handle(Future.failedFuture(it.cause()))
                    }
                })
            } else {
                handler.handle(Future.failedFuture(it.cause()))
            }
        })
    }

    void updateGraknReferenceCounts(Handler<AsyncResult<Void>> handler) {
        postgres.client.getConnection({
            if (it.succeeded()) {
                def connection = it.result()
                connection.prepare("SELECT * FROM function_reference_count", {
                    if (it.succeeded()) {
                        def pq = it.result()
                        def tx = connection.begin()
                        def graknWriteTx = new AtomicReference<>(graknSession.transaction(Grakn.Transaction.Type.WRITE))

                        RowStream<Row> stream = pq.createStream(50, ArrayTuple.EMPTY)
                        stream.exceptionHandler({
                            handler.handle(Future.failedFuture(it))
                        })
                        stream.endHandler(v -> {
                            tx.result().commit()
                            graknWriteTx.get().commit()
                            graknWriteTx.set(null)
                            handler.handle(Future.succeededFuture())
                        })

                        int insertCount = 0
                        stream.handler(row -> {
                            if (insertCount > 0 && insertCount % 500 == 0) {
                                graknWriteTx.get().commit()
                                graknWriteTx.set(graknSession.transaction(Grakn.Transaction.Type.WRITE))
                            }
                            insertCount++

                            graknWriteTx.get().query().delete(parseQuery(
                                    'match $x id ' + row.getString(0) + ', has reference_count $ref_count via $r; delete $r;'
                            ))
                            graknWriteTx.get().query().insert(match(
                                    var("f").iid(row.getString(0))
                            ).insert(
                                    var("f").has("reference_count", row.getLong(1))
                            ))
                        })
                    } else {
                        handler.handle(Future.failedFuture(it.cause()))
                    }
                })
            } else {
                handler.handle(Future.failedFuture(it.cause()))
            }
        })
    }
}
