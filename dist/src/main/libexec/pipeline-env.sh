#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#export PIPELINE_HOME=/home/pipeline
#export PIPELINE_CONF=/etc/pipeline
#export PIPELINE_DATA=/var/run/pipeline
#export PIPELINE_LOG=/var/log/pipeline

#export PIPELINE_MAIN_CLASS="com.streamsets.pipeline.agent.Main"

export PIPELINE_PRE_CLASSPATH=${PIPELINE_PRE_CLASSPATH}

export PIPELINE_POST_CLASSPATH=${PIPELINE_POST_CLASSPATH}

export PIPELINE_JAVA_OPTS="-Xmx1024m ${PIPELINE_JAVA_OPTS}"
