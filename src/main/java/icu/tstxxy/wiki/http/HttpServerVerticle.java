package icu.tstxxy.wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;

import java.util.Arrays;

public class HttpServerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);
    private final String wikiDbQueue = "wikidb.queue";
    private JWTAuth jwtAuth;

    @Override
    public void start(Promise<Void> startPromise) {
        jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions().addPubSecKey(new PubSecKeyOptions()
            .setAlgorithm("HS256").setBuffer("secret")));

        HttpServer server = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
            .setKeyStoreOptions(new JksOptions().setPath("server-keystore.jks").setPassword("secret")));

        server.requestHandler(getRouter()).listen(443).onFailure(e -> {
            LOGGER.error(e.getMessage());
            startPromise.fail(e.getCause());
        });
    }

    private void apiDeletePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        var request = new JsonObject().put("id", id);
        var options = new DeliveryOptions().addHeader("action", "delete-page");

        handleSimpleRequest(context, request, options, 200);
    }

    private void apiUpdatePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        var page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "markdown")) return;
        var request = new JsonObject().put("id", id).put("markdown", page.getString("markdown"));
        var options = new DeliveryOptions().addHeader("action", "save-page");
        handleSimpleRequest(context, request, options, 200);
    }

    private void apiCreatePage(RoutingContext context) {
        JsonObject page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "title", "markdown")) return;
        var options = new DeliveryOptions().addHeader("action", "create-page");
        var request = new JsonObject().put("title", page.getString("title"))
            .put("markdown", page.getString("markdown"));

        handleSimpleRequest(context, request, options, 201);
    }

    private void apiRoot(RoutingContext context) {
        DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages-data");
        vertx.eventBus().request(wikiDbQueue, new JsonObject(), options).compose(
            message -> context.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("success", true).put("pages", message.body()).encode()))
            .onFailure(e -> context.response().setStatusCode(500)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("success", true).put("error", e.getCause()).encode()));
    }

    private void apiGetPage(RoutingContext context) {
        var id = Integer.parseInt(context.request().getParam("id"));
        var options = new DeliveryOptions().addHeader("action", "get-page-by-id");
        vertx.eventBus().request(wikiDbQueue, new JsonObject().put("id", id), options).compose(message -> {
            JsonObject body = (JsonObject) message.body();
            var response = new JsonObject();
            if (body.getBoolean("found")) {
                var payload = new JsonObject()
                    .put("title", body.getString("title"))
                    .put("id", body.getInteger("id"))
                    .put("markdown", body.getString("content"))
                    .put("html", Processor.process(body.getString("content")));
                response.put("success", true).put("page", payload);
                context.response().setStatusCode(200);
            } else {
                response.put("success", false).put("error", "There is no page with ID " + id);
                context.response().setStatusCode(404);
            }
            return context.response().putHeader("Content-Type", "application/json").end(response.encode());
        }).onFailure(e -> context.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", false).put("error", e.getMessage()).encode()));
    }

    private boolean validateJsonPageDocument(RoutingContext context, JsonObject page, String... expectedKeys) {
        if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
            LOGGER.error("Bad page creation JSON payload: "
                + page.encodePrettily() + " from " + context.request().remoteAddress());
            context.response().setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("success", false).put("error", "Bad request payload").encode());
            return false;
        }
        return true;
    }

    private void handleSimpleRequest(RoutingContext context, JsonObject request, DeliveryOptions options, int code) {
        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> context.response()
            .setStatusCode(code).putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("success", true).encode()))
            .onFailure(e -> {
                LOGGER.error(e.getMessage());
                context.response().setStatusCode(500).putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("success", false).put("error", e.getMessage()).encode());
            });
    }

    private void apiToken(RoutingContext context) {
        var request = new JsonObject().put("username", context.request().getParam("username"))
            .put("password", context.request().getParam("password"));
        var options = new DeliveryOptions().addHeader("action", "authenticate");

        vertx.eventBus().request(wikiDbQueue, request, options).compose(message -> {
            String token = jwtAuth.generateToken(new JsonObject().put("username", request.getString("username")),
                new JWTOptions().setSubject("Wiki Api").setIssuer("tstxxy")
            );

            return context.response().putHeader("Content-Type", "text/plain").end(token);
        }).onFailure(e -> {
            context.fail(e.getCause());
            LOGGER.error(e.getMessage());
        });
    }

    private void apiResponse(RoutingContext context, int statusCode, String jsonField, Object jsonData) {
        context.response().setStatusCode(statusCode)
            .putHeader("Content-Type", "application/jsoN");
        JsonObject wrapped = new JsonObject().put("success", true);
        if (jsonField != null && jsonData != null) {
            wrapped.put(jsonField, jsonData);
        }
        context.response().end(wrapped.encode());
    }

    private void apiFailure(RoutingContext context, int statusCode, String error) {
        context.response().setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("success", false)
                .put("error", error).encode());
    }

    private Router getRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // static
        router.get("/app/*").handler(StaticHandler.create().setCachingEnabled(false));
        router.get("/").handler(context -> context.reroute("/app/index.html"));

        // pre-rendering
        router.post("/app/markdown").handler(context -> {
            String html = Processor.process(context.getBodyAsString());
            context.response().putHeader("Content-Type", "text/html")
                .setStatusCode(200)
                .end(html);
        });


        Router apiRouter = Router.router(vertx);
//        apiRouter.route("/pages").handler(JWTAuthHandler.create(jwtAuth));
//        apiRouter.get("/token").handler(this::apiToken);

        apiRouter.get("/pages").handler(this::apiRoot);
        apiRouter.get("/pages/:id").handler(this::apiGetPage);
        apiRouter.post("/pages").handler(this::apiCreatePage);
        apiRouter.put("/pages/:id").handler(this::apiUpdatePage);
        apiRouter.delete("/pages/:id").handler(this::apiDeletePage);
        router.mountSubRouter("/api", apiRouter);
        return router;
    }
}


