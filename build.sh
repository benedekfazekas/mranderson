#!/bin/bash

# runs source-deps and tests and then provided lein target with mranderson

function check_result {
    if [ $? -ne 0 ]; then
        echo "FAILED"
        exit 1
    fi
}

lein do clean, inline-deps :skip-javaclass-repackage true
check_result
lein with-profile plugin.mranderson/config "$@"
