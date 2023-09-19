# Clofuzz

- requirements: Java version >= 21

## build

build an uberjar with the following command

```
clojure -T:build uber 
```
and then run the jar file

```
java -jar target/clofuzz-0.0.xx.jar -h
```

or run direclty with clj as

```
clj -M:run -h
```
