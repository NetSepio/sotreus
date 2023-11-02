#LABEL Maintainer Anish Mishra Name anish@lazarus.network

FROM golang:alpine AS build-app
RUN apk update && apk add --no-cache git
WORKDIR /app
COPY go.mod .
COPY go.sum .
RUN apk add build-base
RUN go mod download
COPY . .
RUN go build -o sotreus . && apk del git

FROM node:18.13.0-alpine AS build-web
WORKDIR /app
COPY webapp/package*.json ./
RUN npm install
COPY webapp/ ./
RUN npm run build

FROM alpine:latest
WORKDIR /app
COPY --from=build-app /app/sotreus .
COPY --from=build-web /app/build ./webapp
COPY --from=build-app /app/start.sh .
COPY wg-watcher.sh .
RUN chmod +x ./sotreus ./wg-watcher.sh
RUN apk update && apk add --no-cache bash openresolv bind-tools wireguard-tools gettext inotify-tools
ENV LOAD_CONFIG_FILE=$LOAD_CONFIG_FILE RUNTYPE=$RUNTYPE SERVER=$SERVER WG_HTTP_PORT=$WG_HTTP_PORT WG_GRPC_PORT=$WG_GRPC_PORT
ENV WG_CONF_DIR=$WG_CONF_DIR WG_KEYS_DIR=$WG_KEYS_DIR WG_INTERFACE_NAME=$WG_INTERFACE_NAME
ENV WG_ENDPOINT_HOST=$WG_ENDPOINT_HOST WG_ENDPOINT_PORT=$WG_ENDPOINT_PORT WG_IPV4_SUBNET=$WG_IPV4_SUBNET WG_IPV6_SUBNET=$WG_IPV6_SUBNET
ENV WG_DNS=$WG_DNS WG_ALLOWED_IP_1=$WG_ALLOWED_IP_1 WG_ALLOWED_IP_2=$WG_ALLOWED_IP_2
ENV WG_PRE_UP=$WG_PRE_UP WG_POST_UP=$WG_POST_UP WG_PRE_DOWN=$WG_PRE_DOWN WG_POST_DOWN=$WG_POST_DOWN
ENV SMTP_HOST=$SMTP_HOST SMTP_PORT=$SMTP_PORT SMTP_USERNAME=$SMTP_USERNAME SMTP_PASSWORD=$SMTP_PASSWORD SMTP_FROM=$SMTP_FROM
ENV PASETO_EXPIRATION_IN_HOURS=$PASETO_EXPIRATION_IN_HOURS SIGNED_BY=$SIGNED_BY FOOTER=$FOOTER AUTH_EULA=$AUTH_EULA MASTERNODE_WALLET=$MASTERNODE_WALLET
ENTRYPOINT ["/app/start.sh"]
