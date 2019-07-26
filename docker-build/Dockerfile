FROM node:11-slim

#https://github.com/puckel/docker-airflow/issues/182#issuecomment-444683455
RUN mkdir -p /usr/share/man/man1
RUN apt-get update && apt-get install bash sudo openjdk-8-jdk -y && \
            apt-get install git -y && \
            apt-get install -y python-dev && \
            apt-get install -y python-pip && \
            git clone --branch master https://github.com/rashmi-sakhalkar/trusted-connector/ && \
            cd /trusted-connector && \
            ./gradlew clean build --parallel

WORKDIR /trusted-connector       

CMD ["./karaf-assembly/build/assembly/bin/karaf","clean","debug"]
