FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
ENV NODE_ENV=production PORT=8080
USER node
EXPOSE 8080
CMD ["node", "src/server.mjs"]
