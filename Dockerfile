FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Badr El Khalyly <badrelkhalylyphd@gmail.com>"
LABEL description="HYQP Broker — Hybrid Query Protocol IoT messaging broker with virtual threads"
LABEL version="1.0.0"

WORKDIR /app

# Copy compiled broker classes
COPY out/ /app/classes/

# Broker data directory for persistence (retained messages, sessions)
RUN mkdir -p /app/data

VOLUME /app/data

# HYQP default port
EXPOSE 4444

# Run with virtual threads (Java 21+)
ENTRYPOINT ["java", "-cp", "/app/classes", "com.hyqp.broker.BrokerMain"]
