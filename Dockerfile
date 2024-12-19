FROM eclipse-temurin:11.0.25_9-jdk-noble

# Note if you update ubuntu the install lines below will need to be changed
ENV CONTAINERD_VER=1.7.24-1
ENV DOCKER_VER=27.4.1-1
ENV DOCKER_BX_VER=0.19.3-1
ENV DOCKER_COMPOSE_VER=2.32.1-1

RUN apt update && apt install -y wget ant make git iptables

# install jars and remove shaded jar containing vulnerable log4j
# TODO GRADLE remove when switching to Gradle
RUN cd /opt && git clone https://github.com/kbase/jars.git \
    && rm jars/lib/jars/dockerjava/docker-java-shaded-3.0.14.jar

# install docker

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
    && dpkg -i $CD $DCE $DCEC $BX $DCM \
    && rm $CD $DCE $DCEC $BX $DCM

ADD . /opt/kb_sdk

# Fix CallbackServer interface
RUN cd /opt/kb_sdk \
   && sed -i 's/en0/eth0/' src/java/us/kbase/common/executionengine/CallbackServer.java \
   && make \
   && rm -rf /src/.git

ENV PATH=$PATH:/opt/kb_sdk/bin

ENTRYPOINT [ "/opt/kb_sdk/entrypoint" ]
