FROM nginx:alpine
COPY . /usr/share/nginx/html/
# Remove the Dockerfile from the served content
RUN rm -f /usr/share/nginx/html/Dockerfile
EXPOSE 80
