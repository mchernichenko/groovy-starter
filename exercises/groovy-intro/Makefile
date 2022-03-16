setup:
	gradle wrapper --gradle-version 7.3.1

clean:
	./gradlew clean

install:
	./gradlew clean install copyResources

run:
	./build/install/app/bin/app classpath=/tmp/logback.xml

test:
	gradle test

