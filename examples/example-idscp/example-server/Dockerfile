FROM node:12-slim

WORKDIR /app

ADD package.json /app
RUN npm install

ADD app.js /app

EXPOSE 8081

ENTRYPOINT [ "node", "app.js" ]
