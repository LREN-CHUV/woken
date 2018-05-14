# Pull base image
FROM hbpmip/scala-base-build:1.1.0-2 as scala-build-env

# First caching layer: build.sbt and sbt configuration
COPY build.sbt /build/
RUN  mkdir -p /build/project/
COPY project/build.properties project/plugins.sbt project/.gitignore /build/project/

# Run sbt on an empty project and force it to download most of its dependencies to fill the cache
RUN sbt -mem 1500 compile

# Second caching layer: project sources
COPY docker/test-in-docker.sh /test.sh
COPY src/test/ /build/src/test/

RUN apk update \
    && apk add --no-cache java-cacerts

RUN set -ex \
	&& apk add -u --no-cache --virtual .build-deps \
		git gcc libc-dev make cmake libtirpc-dev pax-utils \
	&& mkdir -p /usr/src \
	&& cd /usr/src \
	&& git clone --branch sigar-musl https://github.com/ncopa/sigar.git \
	&& mkdir sigar/build \
	&& cd sigar/build \
	&& CFLAGS="-std=gnu89" cmake .. \
	&& make -j$(getconf _NPROCESSORS_ONLN) \
	&& make install \
	&& runDeps="$( \
		scanelf --needed --nobanner --recursive /usr/local \
			| awk '{ gsub(/,/, "\nso:", $2); print "so:" $2 }' \
			| sort -u \
			| xargs -r apk info --installed \
			| sort -u \
	)" \
	&& apk add --virtual .libsigar-rundeps $runDeps \
	&& apk del .build-deps \
    && rm -rf /usr/src/sigar


RUN sbt -mem 1500 test:compile

ENTRYPOINT ["/test.sh"]

# 8197: Akka TCP
EXPOSE 8197
