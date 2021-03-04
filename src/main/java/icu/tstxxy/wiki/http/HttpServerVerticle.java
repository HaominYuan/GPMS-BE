package icu.tstxxy.wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;

import java.util.Date;


public class HttpServerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);
    private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n\nFeel-free to write in Markdown!\n";

    public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
    public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

    private String wikiDbQueue = "wikidb.queue";
    private FreeMarkerTemplateEngine templateEngine;

    @Override
    public void start(Promise<Void> startPromise) {
        wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, wikiDbQueue);

        Router router = Router.router(vertx);
        router.get("/").handler(this::indexHandler);
        router.get("/wiki/:page").handler(this::pageRenderingHandler);
        router.post().handler(BodyHandler.create());
        router.post("/save").handler(this::pageUpdateHandler);
        router.post("/create").handler(this::pageCreateHandler);
        router.post("/delete").handler(this::pageDeletionHandler);

        templateEngine = FreeMarkerTemplateEngine.create(vertx);

        int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 80);

        HttpServer server = vertx.createHttpServer();
        server.requestHandler(router).listen(portNumber).onFailure(e -> {
            LOGGER.error(e.getMessage());
            startPromise.fail(e.getCause());
        });
    }

    private void indexHandler(RoutingContext context) {
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages");
        vertx.eventBus().request(wikiDbQueue, new JsonObject(), options).compose(message -> {
            JsonObject body = (JsonObject) message.body();
            context.put("title", "Wiki home");
            context.put("pages", body.getJsonArray("pages").getList());

            return templateEngine.render(context.data(), "templates/index.ftl")
                .compose(buffer -> context.response().putHeader("Content-Type", "text/html").end(buffer));
        }).onFailure(e -> {
            LOGGER.error(e.getMessage());
            context.fail(e.getCause());
        });
    }

    private void pageRenderingHandler(RoutingContext context) {
        // 请求链接拿数据
        String requestedPage = context.request().getParam("page");
        // 设置数据头
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "get-page");
        // 通过 json 格式发送数据
        JsonObject request = new JsonObject().put("page", requestedPage);
        // 通过 eventbus 发送请求
        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> {
            JsonObject body = (JsonObject) message.body();
            boolean found = body.getBoolean("found");
            String rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN);

            context.put("title", requestedPage)
                .put("id", body.getInteger("id", -1))
                .put("newPage", found ? "no" : "yes")
                .put("rawContent", rawContent)
                .put("content", Processor.process(rawContent))
                .put("timestamp", new Date().toString());

            return templateEngine.render(context.data(), "templates/page.ftl")
                .compose(buffer -> context.response().putHeader("Content-Type", "text/html").end(buffer));
        }).onFailure(e -> {
            context.fail(e.getCause());
            LOGGER.error(e.getMessage());
        });
    }

    private void pageUpdateHandler(RoutingContext context) {
        String title = context.request().getParam("title");
        JsonObject request = new JsonObject()
            .put("id", context.request().getParam("id"))
            .put("title", title)
            .put("markdown", context.request().getParam("markdown"));


        DeliveryOptions options = new DeliveryOptions();
        if ("yes".equals(context.request().getParam("newPage"))) {
            options.addHeader("action", "create-page");
        } else {
            options.addHeader("action", "save-page");
        }

        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> context.response()
            .setStatusCode(303)
            .putHeader("Location", "/wiki/" + title)
            .end())
            .onFailure(e -> {
            context.fail(e.getCause());
            LOGGER.error(e.getMessage());
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
            .end().onFailure(e -> {
            context.fail(e);
            LOGGER.error(e);
        });
    }

    private void pageDeletionHandler(RoutingContext context) {
        String id = context.request().getParam("id");
        JsonObject request = new JsonObject().put("id", id);
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "delete-page");
        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> context.response()
            .setStatusCode(303)
            .putHeader("Location", "/")
            .end()).onFailure(e -> {
            context.fail(e);
            LOGGER.error(e);
        });
    }
}
