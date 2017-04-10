# Changes

## 0.0.6 (2017-04-10)

* Bugfix: Don't use a Reader to read HTTP headers, since it may consume too much data, which is
  problematic for a fallback handler that wants to read a POST request.
* Bugfix: Parse the results from the Autobahn test suite correctly.

## 0.0.5 (2017-04-10)

* Support for fallback handler. A fallback handler is invoked for all endpoints that don't have a
  WebSocket handler.

## 0.0.4 (2017-03-24)

* SSL support

## 0.0.3 (2017-03-12)

* Don't use a separate Executor for invoking handlers&mdash;async handler invocation created concurrency problems.
* Performance & memory improvements
* WebSocketHandler.onTextMessage received a `CharSequence` instead of a `String`.

## 0.0.2 (2017-03-11)

* Set TCP_NODELAY (disable Nagle's algorithm) since otherwise Autobahn tests fail/hang on Linux.

## 0.0.1 (2017-03-09)

* First released version
