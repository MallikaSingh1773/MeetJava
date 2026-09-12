@echo off
REM Windows launcher for MeetJava. Needs JDK 21+ and Maven on PATH.
echo Starting MeetJava on http://localhost:8080 ...
call mvn spring-boot:run
pause
