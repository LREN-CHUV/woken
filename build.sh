#!/bin/bash -e
sbt assembly
cp target/scala-2.11/workflow-assembly-0.1.jar docker/downloads/workflow.jar
cp target/scala-2.11/workflow-assembly-0.1.jar dev-tests/workflow/lib/workflow.jar
