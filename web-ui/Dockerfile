# Build stage.
FROM node:22-alpine AS build
WORKDIR /app
COPY web-ui/package.json web-ui/package-lock.json ./
RUN npm ci
COPY web-ui/ ./
RUN npm run build

# Runtime stage: static files plus /api and /ws proxy to the backend.
FROM nginx:1.27-alpine
COPY web-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/web-ui/browser /usr/share/nginx/html
EXPOSE 80
