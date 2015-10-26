#!/bin/sh
sbt assembly
cp target/scala-2.11/workflow-assembly-0.1.jar ../docker-containers/mip_federation/workflow/downloads/workflow.jar
