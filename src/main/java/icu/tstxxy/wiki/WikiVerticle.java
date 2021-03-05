package icu.tstxxy.wiki;

import com.github.rjeschke.txtmark.Processor;
import icu.tstxxy.wiki.database.DatabaseVerticle;
import icu.tstxxy.wiki.http.HttpServerVerticle;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
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
        Promise<String> dbVerticleDeployment = Promise.promise();
        vertx.deployVerticle(new DatabaseVerticle(), dbVerticleDeployment);
        dbVerticleDeployment.future().compose(id -> {
            Promise<String> httpVerticleDeployment = Promise.promise();
            vertx.deployVerticle(HttpServerVerticle.class, new DeploymentOptions().setInstances(2),
                httpVerticleDeployment);
            return httpVerticleDeployment.future();
        }).onSuccess(s -> {
            LOGGER.info(s);
            startPromise.complete();
        }).onFailure(e -> {
            LOGGER.error(e.getMessage());
            startPromise.fail(e.getCause());
        });
    }
}
