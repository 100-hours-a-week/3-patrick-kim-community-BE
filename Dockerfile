FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Add non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy JAR file
COPY build/libs/*.jar app.jar

# Set ownership
RUN chown -R spring:spring /app

USER spring

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# JVM options for performance
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Run application with JVM options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]