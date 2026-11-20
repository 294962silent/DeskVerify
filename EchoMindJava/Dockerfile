FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS=""
COPY --from=build /workspace/target/echomind-java-0.1.2.jar /app/echomind-java.jar
COPY models/bge-small-zh-v1.5 /app/models/bge-small-zh-v1.5
COPY skills /app/skills
ENV RAG_EMBEDDING_MODEL="bge-small-zh-v1.5-onnx-fp32"
ENV RAG_EMBEDDING_MODEL_PATH="/app/models/bge-small-zh-v1.5/model.onnx"
ENV RAG_TOKENIZER_PATH="/app/models/bge-small-zh-v1.5/tokenizer.json"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/echomind-java.jar"]
