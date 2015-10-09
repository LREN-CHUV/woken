#!/bin/sh
sbt package
cp target/scala-2.11/workflow-assembly-0.1.jar ../docker-containers/hbp_federation/workflow/downloads/workflow.jar

