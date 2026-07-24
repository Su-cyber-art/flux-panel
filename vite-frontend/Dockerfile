ARG APP_VERSION=dev

FROM node:24-alpine AS builder

ARG APP_VERSION
ENV VITE_APP_VERSION=${APP_VERSION}

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:stable-alpine AS production-stage

ARG APP_VERSION
LABEL org.opencontainers.image.version=${APP_VERSION}

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
