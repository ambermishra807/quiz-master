# AI Quiz Master (Java / Spring Boot)

Requirements: JDK 17+, Maven 3.8+, an Anthropic API key.

## Run
    export ANTHROPIC_API_KEY=your_key        # Windows PowerShell: $env:ANTHROPIC_API_KEY="your_key"
    mvn spring-boot:run
Open http://localhost:8080

## Build a jar
    mvn clean package
    java -jar target/quiz-master-1.0.0.jar

## Structure
- QuizController: POST /api/quiz (validates input)
- QuizService: one compact Anthropic call per quiz, response validation, in-memory cache
- static/index.html: UI, timers, shuffling (questions, options, balanced A-D), results

Optional: ANTHROPIC_MODEL overrides the default model. The API key stays on the server.
