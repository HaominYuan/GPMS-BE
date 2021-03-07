'use strict';

function generateUUID() {
    let d = new Date().getTime();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
        let r = (d + Math.random() * 16) % 16 | 0;
        d = Math.floor(d / 16);
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
}

angular.module("wikiApp", [])
    .controller("WikiController", ["$scope", "$http", "$timeout", function ($scope, $http, $timeout) {

        let DEFAULT_PAGENAME = "Example page";
        let DEFAULT_MARKDOWN = "# Example page\n\nSome text _here_.\n";

        $scope.newPage = function () {
            $scope.pageId = undefined;
            $scope.pageName = DEFAULT_PAGENAME;
            $scope.pageMarkdown = DEFAULT_MARKDOWN;
        };

        $scope.reload = function () {
            $http.get("/api/pages").then(function (response) {
                $scope.pages = response.data.pages.map(({title, content, ...rest}) => {
                    return {
                        name: title,
                        markdown: content,
                        ...rest
                    };
                });
            });
        };

        $scope.pageExists = function () {
            return $scope.pageId !== undefined;
        };

        $scope.load = function (id) {
            $scope.pageModified = false;
            $http.get("/api/pages/" + id).then(function (response) {
                let page = response.data.page;
                $scope.pageId = page.id;
                $scope.pageName = page.name;
                $scope.pageMarkdown = page.markdown;
                $scope.updateRendering(page.html);
            });
        };

        $scope.updateRendering = function (html) {
            document.getElementById("rendering").innerHTML = html;
        };

        $scope.save = function () {
            let payload;
            if ($scope.pageId === undefined) {
                payload = {
                    "title": $scope.pageName,
                    "content": $scope.pageMarkdown
                };
                $http.post("/api/pages", payload).then(function (ok) {
                    $scope.reload();
                    $scope.success("Page created");
                    var guessMaxId = _.maxBy($scope.pages, function (page) {
                        return page.id;
                    });
                    $scope.load(guessMaxId.id || 0);
                }, function (err) {
                    $scope.error(err.data.error);
                });
            } else {
                let payload = {
                    "client": clientUuid,
                    "markdown": $scope.pageMarkdown
                };
                $http.put("/api/pages/" + $scope.pageId, payload).then(function (ok) {
                    $scope.success("Page saved");
                }, function (err) {
                    $scope.error(err.data.error);
                });
            }
        };

        $scope.delete = function () {
            $http.delete("/api/pages/" + $scope.pageId).then(function (ok) {
                $scope.reload();
                $scope.newPage();
                $scope.success("Page deleted");
            }, function (err) {
                $scope.error(err.data.error);
            });
        };

        $scope.success = function (message) {
            $scope.alertMessage = message;
            let alert = document.getElementById("alertMessage");
            alert.classList.add("alert-success");
            alert.classList.remove("invisible");
            $timeout(function () {
                alert.classList.add("invisible");
                alert.classList.remove("alert-success");
            }, 3000);
        };

        $scope.error = function (message) {
            $scope.alertMessage = message;
            let alert = document.getElementById("alertMessage");
            alert.classList.add("alert-danger");
            alert.classList.remove("invisible");
            $timeout(function () {
                alert.classList.add("invisible");
                alert.classList.remove("alert-danger");
            }, 5000);
        };

        $scope.reload();
        $scope.newPage();

        let markdownRenderingPromise = null;
        $scope.$watch("pageMarkdown", function (text) {
            if (eb.state !== EventBus.OPEN) return;
            if (markdownRenderingPromise !== null) {
                $timeout.cancel(markdownRenderingPromise);
            }
            markdownRenderingPromise = $timeout(function () {
                markdownRenderingPromise = null;
                // tag::eventbus-markdown-sender[]
                eb.send("app.markdown", text, function (err, reply) { // <1>
                    if (err === null) {
                        $scope.$apply(function () { // <2>
                            $scope.updateRendering(reply.body); // <3>
                        });
                    } else {
                        console.warn("Error rendering Markdown content: " + JSON.stringify(err));
                    }
                });
                // end::eventbus-markdown-sender[]
            }, 300);
        });

        // tag::event-bus-js-setup[]
        let eb = new EventBus(window.location.protocol + "//" + window.location.host + "/eventbus");
        // end::event-bus-js-setup[]
        // tag::register-page-saved-handler[]
        let clientUuid = generateUUID(); // <1>
        eb.onopen = function () {
            eb.registerHandler("page.saved", function (error, message) { // <2>
                if (message.body // <3>
                    && $scope.pageId === message.body.id // <4>
                    && clientUuid !== message.body.client) { // <5>
                    $scope.$apply(function () { // <6>
                        $scope.pageModified = true; // <7>
                    });
                }
            });
        };
        // end::register-page-saved-handler[]

    }]);
