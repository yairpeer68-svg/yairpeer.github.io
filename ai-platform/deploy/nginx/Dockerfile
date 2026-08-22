FROM node:22-alpine AS admin-build
WORKDIR /admin
COPY admin/package*.json ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY admin/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
RUN rm -f /etc/nginx/conf.d/default.conf
# Rendered by the image entrypoint through envsubst so ENGINEERING_MAX_ARCHIVE_MB
# stays in sync with the API's ENGINEERING_MAX_ARCHIVE_BYTES.
COPY deploy/nginx/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=admin-build /admin/dist /usr/share/nginx/html/admin
ENV ENGINEERING_MAX_ARCHIVE_MB=100
