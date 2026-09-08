FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY app/ /app/
USER 10001:10001
ENTRYPOINT ["/app/bin/tennis-settlements-bot"]
CMD ["bot"]
