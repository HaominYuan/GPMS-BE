package tstxxy.gra;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.*;
import java.util.stream.Collectors;


public class WikiVerticle extends AbstractVerticle {
    private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id serial primary key, Name varchar(255) unique, Content text);";
    private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name=$1"; // <1>

    private static final String SQL_CREATE_PAGE = "insert into Pages values (default, $1, $2)";
    private static final String SQL_SAVE_PAGE = "update Pages set Content = $1 where Id = $2";

    private static final String SQL_ALL_PAGES = "select Name from Pages";
    private static final String SQL_DELETE_PAGE = "delete from Pages where id=$1";
    private static final String EMPTY_PAGE_MARKDOWN =
        "# A new page\n\nFeel-free to write in Markdown!\n";

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiVerticle.class);
    private FreeMarkerTemplateEngine templateEngine;

    private PoolOptions poolOptions = new PoolOptions().setMaxSize(5);
    private PgConnectOptions connectOptions = new PgConnectOptions()
        .setPort(5432)
        .setHost("localhost")
        .setDatabase("wiki")
        .setUser("postgres")
        .setPassword("qwer1234");
    private PgPool dbClient;


    @Override
    public void start(Promise<Void> startPromise) {
        Future<Void> steps = prepareDatabase().compose(v -> startHttpServer());

        steps.onFailure(e -> {
            LOGGER.error(e.getMessage());
            LOGGER.error("here");
        });
    }

    private Future<Void> prepareDatabase() {
        Promise<Void> promise = Promise.promise();
        dbClient = PgPool.pool(vertx, connectOptions, poolOptions);

        dbClient.getConnection().compose(conn -> conn.query(SQL_CREATE_PAGES_TABLE)
            .execute().eventually(v -> conn.close())
        ).onFailure(e -> {
            LOGGER.error(e.getCause());
            promise.fail(e.getCause());
        }).onSuccess(result -> {
            promise.complete();
        });

        return promise.future();
    }

    private Future<Void> startHttpServer() {
        Promise<Void> promise = Promise.promise();

        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        router.get("/").handler(this::indexHandler);
        router.get("/wiki/:page").handler(this::pageRenderingHandler);
        router.post().handler(BodyHandler.create());
        router.post("/save").handler(this::pageUpdateHandler);
        router.post("/create").handler(this::pageCreateHandler);
        router.post("/delete").handler(this::pageDeletionHandler);

        templateEngine = FreeMarkerTemplateEngine.create(vertx);

        server.requestHandler(router).listen(80, ar -> {
            if (ar.succeeded()) {
                LOGGER.info("HTTP server running on port 80");
                promise.complete();
            } else {
                LOGGER.error("Could not start a HTTP server", ar.cause());
                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    private void indexHandler(RoutingContext context) {
        dbClient.getConnection().compose(conn -> conn.query(SQL_ALL_PAGES)
            .execute().onSuccess(rs -> {
                var it = rs.iterator();
                final List<String> pages = new ArrayList<>();
                rs.forEach(row -> {
                    pages.add(row.getString("name"));
                });

                context.put("title", "Wiki home")
                    .put("pages", pages.stream().sorted().collect(Collectors.toList()));

                templateEngine.render(context.data(), "templates/index.ftl").onSuccess(buffer -> {
                    context.response().putHeader("Context-Type", "text/html").end(buffer);
                }).onFailure(e -> {
                    LOGGER.error(e.getMessage());
                    context.fail(e.getCause());
                });
            }).onFailure(e -> {
                LOGGER.error(e.getMessage());
                context.fail(e.getCause());
            }).eventually(v -> conn.close())
        ).onFailure(e -> {
            LOGGER.error(e.getMessage());
            context.fail(e.getCause());
        });

    }

    private void pageDeletionHandler(RoutingContext context) {
        String id = context.request().getParam("id");
        dbClient.getConnection().compose(conn -> conn.preparedQuery(SQL_DELETE_PAGE)
            .execute(Tuple.of(Integer.parseInt(id)))
            .onSuccess(v -> context.response().setStatusCode(303).putHeader("Location", "/").end())
            .onFailure(e -> {
                LOGGER.error(e.getMessage());
                context.fail(e.getCause());
            }).eventually(v -> conn.close())
        ).onFailure(e -> {
            LOGGER.error(e.getMessage());
            context.fail(e.getCause());
        });
    }

    private void pageRenderingHandler(RoutingContext context) {
        String page = context.request().getParam("page");

        dbClient.getConnection().compose(conn -> conn.preparedQuery(SQL_GET_PAGE)
            .execute(Tuple.of(page)).onSuccess(rs -> {
                var it = rs.iterator();

                context.put("title", page)
                    .put("timestamp", new Date().toString());

                if (it.hasNext()) {
                    var row = it.next();
                    var id = row.getInteger(0);
                    var rawContent = row.getString(1);

                    context.put("id", id)
                        .put("newPage", "no")
                        .put("rawContent", rawContent)
                        .put("content", Processor.process(rawContent));
                } else {
                    context.put("id", -1)
                        .put("newPage", "yes")
                        .put("rawContent", EMPTY_PAGE_MARKDOWN)
                        .put("content", Processor.process(EMPTY_PAGE_MARKDOWN));
                }

                templateEngine.render(context.data(), "templates/page.ftl").onSuccess(buffer -> {
                    context.response().putHeader("Content-Type", "text/html").end(buffer);
                }).onFailure(e -> {
                    LOGGER.error(e.getMessage());
                    context.fail(e.getCause());
                });
            }).onFailure(e -> {
                LOGGER.error(e.getCause());
                context.fail(e.getCause());
            }).eventually(v -> conn.close())
        ).onFailure(e -> {
            LOGGER.error(e.getCause());
            context.fail(e.getCause());
        });
    }

    private void pageUpdateHandler(RoutingContext context) {
        Integer id = Integer.valueOf(context.request().getParam("id"));
        String title = context.request().getParam("title");
        String markDown = context.request().getParam("markdown");
        boolean newPage = "yes".equals(context.request().getParam("newPage"));

        dbClient.getConnection().compose(conn -> {
            Future<RowSet<Row>> result;
            if (newPage) {
                result = conn.preparedQuery(SQL_CREATE_PAGE).execute(Tuple.of(title, markDown));
            } else {
                result = conn.preparedQuery(SQL_SAVE_PAGE).execute(Tuple.of(markDown, id));
            }

            return result.onSuccess(rs -> {
                context.response().setStatusCode(303)
                    .putHeader("Location", "/wiki/" + title)
                    .end();
            }).onFailure(e -> {
                LOGGER.error(e.getMessage());
                context.fail(e.getCause());
            }).eventually(v -> conn.close());
        }).onFailure(e -> {
            LOGGER.error(e.getMessage());
            context.fail(e.getCause());
        });
    }

    private void pageCreateHandler(RoutingContext context) {
        String pageName = context.request().getParam("name");
        String location = "/wiki/" + pageName;
        if (pageName == null || pageName.isEmpty()) {
            location = "/";
        }
        context.response().setStatusCode(303)
            .putHeader("Location", location)
            .end();
    }
}
