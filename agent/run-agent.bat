@echo off
REM Builds the agent if needed, then runs it. Requires JDK 21+ and Maven.
if not exist target\meetjava-agent.jar (
    echo Building the agent, this happens once...
    call mvn -q package
)
java -jar target\meetjava-agent.jar
