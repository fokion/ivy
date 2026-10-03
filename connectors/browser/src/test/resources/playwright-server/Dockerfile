# A Playwright server for the integration tests, built from Docker Hub images: the official image
# (mcr.microsoft.com/playwright) is the same thing when that registry is reachable.
FROM node:22-bookworm-slim
ARG PLAYWRIGHT_VERSION
RUN npm install -g playwright@${PLAYWRIGHT_VERSION} \
    && playwright install --with-deps chromium \
    && rm -rf /root/.npm /var/lib/apt/lists/*
EXPOSE 3000
CMD ["playwright", "run-server", "--port", "3000", "--host", "0.0.0.0"]
