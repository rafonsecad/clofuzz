#!/usr/bin/env bash

clj -T:build uber

JAR=$(find ./target -name *.jar)

VERSION=${JAR//.\/target\/clofuzz-/}
VERSION=${VERSION//.jar/}
VERSION=${VERSION//-/_}

jpackage --input ./ \
	 --name clofuzz \
	 --app-version $VERSION \
	 --main-jar $JAR \
	 --main-class clojure.main \
	 --arguments m \
	 --arguments fuzz.core \
	 --type rpm \
	 --dest ./target/
