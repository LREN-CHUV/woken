#!/bin/bash

# Linear regression with many covariables and many groupings

http -v --timeout 180 POST localhost:8087/mining \
         variables:='[{"code":"rightsmcsupplementarymotorcortex"}]' \
         grouping:='[{"code":"AGE"},{"code":"ptgender"}]' \
         covariables:='[{"code":"leftmprgprecentralgyrusmedialsegment"},{"code":"rightporgposteriororbitalgyrus"},{"code":"rightaorganteriororbitalgyrus"}]' \
         filters:='[]' \
         algorithm:='{"code":"linearRegression", "label": "linearRegression", "parameters": []}'
