FROM node:11-slim

#https://github.com/puckel/docker-airflow/issues/182#issuecomment-444683455
RUN mkdir -p /usr/share/man/man1
RUN apt-get update -qq && apt-get install -qq bash sudo openjdk-8-jdk

COPY run.sh .
RUN chmod +x run.sh

RUN git clone --branch master https://github.com/rashmi-sakhalkar/trusted-connector/

ENV GRADLE_OPTS: "-Dorg.gradle.daemon=false"
ENV PROJECT_DIR: "/trusted-connector"
ENV GRADLE_DIR: "/.gradle"
ENV M2_DIR: "/.m2"

ENTRYPOINT ["/run.sh"]
CMD ["build", "check", "dockerize", "--parallel", "-PdockerTag=develop"]
