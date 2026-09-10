FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY app/lib/ /app/lib/
COPY app/bin/ /app/bin/
COPY app/tennis-settlements-bot.jar /app/lib/tennis-settlements-bot.jar
USER 10001:10001
ENTRYPOINT ["/app/bin/tennis-settlements-bot"]
CMD ["bot"]
