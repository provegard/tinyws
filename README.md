# TinyWS

[![Build Status](https://travis-ci.org/provegard/tinyws.svg?branch=master)](https://travis-ci.org/provegard/tinyws)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.programmaticallyspeaking/tinyws/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.programmaticallyspeaking/tinyws)

## What is it?

A tiny WebSocket server written in Java 8.

## Why?

Because it's fun and I wanted to be able to have a WebSocket server without
several megabytes of dependencies.

## Status

Version 0.0.6.

It passes all tests of [Autobahn|Testsuite](https://github.com/crossbario/autobahn-testsuite) version
0.7.5, except 12.\* and 13.\* (compression using the permessage-deflate extension).

Features:

* Standards-compliant (according to AutoBahn|Testsuite at least)
* Tiny&mdash;one file
* NO external dependencies
* Requires Java 8
* Multiple endpoints
* Configurable Maximum frame size (controls fragmentation)
* Configurable address and port
* Configurable backlog
* Logging via simple interface&mdash;no dependency on any particilar log framework
* SSL (WSS) support
* Fallback handler, for endpoints without a WebSocket handler

Limitations:

* No frame compression support
* No extension support
* Maximum payload size is 0x7fffffff (2147483647) bytes
* Only talks protocol version 13 (mandated by [RFC 6455](https://tools.ietf.org/html/rfc6455))

## Running tests

    ./gradlew test
    
Note that the tests requires [wstest](https://github.com/crossbario/autobahn-testsuite) to be
installed and available on the path.
    
## Examples

The _echoserver_ folder contains an&mdash;drum roll&mdash;echo server!

## Autobahn test suite

Create an Autobahn configuration file named _fuzzingclient.json_, e.g.:

    {
      "servers": [{ "url": "ws://127.0.0.1:9001" }],
      "cases": ["*"],
    }

Run the echo server (which starts on port 9001):

    ./gradlew run
    
Run _wstest_:

    wstest -m fuzzingclient

For SSL, change _fuzzingclient.json_ so that it uses a **wss** URL instead:

    {
      "servers": [{ "url": "wss://127.0.0.1:9001" }],
      "cases": ["*"],
    }

...and then run the echo server like this:

    ./gradlew -Pargs="wss/keystore.jks storepassword keypassword" run

(Yes, those are the actual passwords&mdash;they're not placeholders.)
 

## Usage/documentation

TBD

## Changelog

See CHANGELOG.md.

## Author

Per Rovegård

Twitter: @provegard

## License

MIT, https://per.mit-license.org/2017

See also the LICENSE file.

