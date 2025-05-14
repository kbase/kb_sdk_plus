FROM eclipse-temurin:17.0.15_6-jdk-noble AS build
# Ubuntu 24.04 LTS noble ^^

RUN apt update && apt install -y git

WORKDIR /tmp/kbsdk

# dependencies take a while to D/L, so D/L & cache before the build so code changes don't cause
# a new D/L
# can't glob *gradle because of the .gradle dir
COPY build.gradle gradlew settings.gradle /tmp/kbsdk
COPY gradle/ /tmp/kbsdk/gradle/
RUN ./gradlew dependencies

# Now build the code
# for the git commit
COPY .git /tmp/kbsdk/.git/
COPY src /tmp/kbsdk/src/
RUN ./gradlew prepareRunnableDir


FROM eclipse-temurin:17.0.15_6-jdk-noble

# install docker
# spent too much time trying to d/l in the build step and install the debs here, not worth it

# Docker CE requires iptables
RUN apt update && apt install -y iptables wget && rm -rf /var/lib/apt/lists/*

# Note if you update ubuntu the install lines below will need to be changed
ENV CONTAINERD_VER=1.7.24-1
ENV DOCKER_VER=27.4.1-1
ENV DOCKER_BX_VER=0.19.3-1
ENV DOCKER_COMPOSE_VER=2.32.1-1

WORKDIR /opt/docker

RUN DL=https://download.docker.com/linux/ubuntu/dists/noble/pool/stable/amd64 \
    && CD=containerd.io_${CONTAINERD_VER}_amd64.deb \
    && DCE=docker-ce_${DOCKER_VER}~ubuntu.24.04~noble_amd64.deb \
    && DCEC=docker-ce-cli_${DOCKER_VER}~ubuntu.24.04~noble_amd64.deb \
    && BX=docker-buildx-plugin_${DOCKER_BX_VER}~ubuntu.24.04~noble_amd64.deb \
    && DCM=docker-compose-plugin_${DOCKER_COMPOSE_VER}~ubuntu.24.04~noble_amd64.deb \
    && wget -q $DL/$CD \
    && wget -q $DL/$DCE \
    && wget -q $DL/$DCEC \
    && wget -q $DL/$BX \
    && wget -q $DL/$DCM \
    && dpkg -i * \
    && rm *

WORKDIR /opt/kb_sdk

COPY --from=build /tmp/kbsdk/build/runnable /opt/kb_sdk
COPY entrypoint /opt/kb_sdk
ENV PATH=$PATH:/opt/kb_sdk

ENTRYPOINT [ "/opt/kb_sdk/entrypoint" ]
