package icu.tstxxy.wiki.database;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class DatabaseVerticle extends AbstractVerticle {
    public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";
    public static final String CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE = "wikidb.sqlqueries.resource.file";
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseVerticle.class);

    private PgPool dbClient;

    private final HashMap<SqlQuery, String> sqlQueries = new HashMap<>();

    private void loadSqlQueries() throws IOException {
        String queriesFile = config().getString(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE);
        InputStream queriesInputStream;
        if (queriesFile != null) {
            queriesInputStream = new FileInputStream(queriesFile);
        } else {
            queriesInputStream = getClass().getResourceAsStream("/db-queries.properties");
        }

        Properties queriesProps = new Properties();
        queriesProps.load(queriesInputStream);
        queriesInputStream.close();

        sqlQueries.put(SqlQuery.CREATE_PAGES_TABLE, queriesProps.getProperty("create-pages-table"));
        sqlQueries.put(SqlQuery.ALL_PAGES, queriesProps.getProperty("all-pages"));
        sqlQueries.put(SqlQuery.ALL_PAGES_DATA, queriesProps.getProperty("all-pages-data"));
        sqlQueries.put(SqlQuery.GET_PAGE, queriesProps.getProperty("get-page"));
        sqlQueries.put(SqlQuery.CREATE_PAGE, queriesProps.getProperty("create-page"));
        sqlQueries.put(SqlQuery.SAVE_PAGE, queriesProps.getProperty("save-page"));
        sqlQueries.put(SqlQuery.DELETE_PAGE, queriesProps.getProperty("delete-page"));
    }

    public void start(Promise<Void> promise) throws IOException {
        loadSqlQueries();

        dbClient = PgPool.pool(vertx, new PgConnectOptions()
            .setPort(5432)
            .setHost("localhost")
            .setDatabase("wiki")
            .setUser("postgres")
            .setPassword("qwer1234"), new PoolOptions().setMaxSize(5));

        dbClient.getConnection().compose(conn -> {
            var result = conn.query(sqlQueries.get(SqlQuery.CREATE_PAGES_TABLE)).execute();
            conn.close();
            return result;
        }).onFailure(e -> {
            LOGGER.error(e.getMessage());
            promise.fail(e.getCause());
        }).onSuccess(e -> {
            vertx.eventBus().consumer(config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue"), this::onMessage);
            promise.complete();
        });
    }

    public void onMessage(Message<JsonObject> message) {
        if (!message.headers().contains("action")) {
            LOGGER.error(String.format("No action header specified for message with headers %1 and body %2", message.headers(), message.body().encodePrettily()));
            message.fail(ErrorCode.NO_ACTION_SPECIFIED.ordinal(), "No action header specified");
            return;
        }

        String action = message.headers().get("action");

        switch (action) {
            case "all-pages":
                fetchAllPages(message);
                break;
            case "get-page":
                fetchPage(message);
                break;
            case "create-page":
                createPage(message);
                break;
            case "save-page":
                savePage(message);
                break;
            case "delete-page":
                deletePage(message);
                break;
            case "all-pages-data":
                fetchAllPagesData(message);
                break;
            default:
                message.fail(ErrorCode.BAD_ACTION.ordinal(), "Bad action: " + action);
        }
    }

    private void deletePage(Message<JsonObject> message) {
        dbClient.preparedQuery(sqlQueries.get(SqlQuery.DELETE_PAGE))
            .execute(Tuple.of(message.body().getString("id")))
            .onSuccess(rs -> message.reply("ok"))
            .onFailure(e -> reportQueryError(message, e.getCause()));
    }

    private void createPage(Message<JsonObject> message) {
        JsonObject request = message.body();

        dbClient.preparedQuery(sqlQueries.get(SqlQuery.CREATE_PAGE))
            .execute(Tuple.of(request.getString("title"), request.getString("markdown")))
            .onSuccess(rs -> message.reply("ok")).onFailure(e -> reportQueryError(message, e.getCause()));
    }

    private void savePage(Message<JsonObject> message) {
        JsonObject request = message.body();
        dbClient.preparedQuery(sqlQueries.get(SqlQuery.SAVE_PAGE))
            .execute(Tuple.of(request.getString("markdown"), request.getString("id")))
            .onSuccess(rs -> message.reply("ok")).onFailure(e -> reportQueryError(message, e.getCause()));
    }

    private void fetchPage(Message<JsonObject> message) {
        String page = message.body().getString("page");
        dbClient.preparedQuery(sqlQueries.get(SqlQuery.GET_PAGE)).execute(Tuple.of(page)).onSuccess(rs -> {
            var it = rs.iterator();
            var response = new JsonObject();
            if (!it.hasNext()) {
                response.put("found", false);
            } else {
                response.put("found", true);
                var row = it.next();
                response.put("id", row.getInteger(0));
                response.put("rawContent", row.getString(1));
            }
            message.reply(response);
        }).onFailure(e -> reportQueryError(message, e));
    }

    private void fetchAllPages(Message<JsonObject> message) {
        dbClient.query(sqlQueries.get(SqlQuery.ALL_PAGES)).execute().onSuccess(rs -> {
            var it = rs.iterator();
            final List<String> pages = new ArrayList<>();
            rs.forEach(row -> pages.add(row.getString("name")));
            message.reply(new JsonObject().put("pages", new JsonArray(pages)));
        }).onFailure(e -> reportQueryError(message, e));
    }

    private void fetchAllPagesData(Message<JsonObject> message) {
        dbClient.query(sqlQueries.get(SqlQuery.ALL_PAGES_DATA)).execute().onSuccess(rs -> {
            var it = rs.iterator();
            var pages = new JsonArray();
            rs.forEach(row -> pages.add(
                new JsonObject()
                    .put("name", row.getString("name"))
                    .put("content", row.getString("content"))
            ));
            message.reply(pages);
        }).onFailure(e -> reportQueryError(message, e));
    }

    private void reportQueryError(Message<JsonObject> message, Throwable cause) {
        LOGGER.error(Thread.currentThread().getStackTrace()[2].getMethodName() + " Database query error", cause);
        message.fail(ErrorCode.DB_ERROR.ordinal(), cause.getMessage());
    }
}
