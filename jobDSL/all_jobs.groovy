// Your GitHub username, pass it as a parameter to this seed job
// GITHUB_USERNAME="john-doe"

projectName = "webserver"
repositoryUrl = "https://github.com/${GITHUB_USERNAME}/gowebserver.git"

buildJobName = "1.build-${projectName}_GEN"
testJobName = "2.test-${projectName}_GEN"
releaseJobName = "3.release-${projectName}_GEN"
viewName = "${projectName}-jobs_GEN"
pipelineName = "${projectName}-pipeline_GEN"

job(buildJobName) {
    logRotator(-1, 5, -1, -1)
    Utils.configureGit(it, "${repositoryUrl}")
    Utils.configureEnv(it, "${GITHUB_USERNAME}")
    steps {
        shell('''\
            echo "version=\$(cat version.txt)" > props.env
            sudo docker build --no-cache -t ${GITHUB_USERNAME}/http-app:snapshot .
            imageid=$(docker images | grep ${GITHUB_USERNAME}/http-app | grep snapshot | awk '{print $3}')
            cid=$(sudo docker ps --filter="name=testing-app" -q -a)
            if [ ! -z "$cid" ]
            then
                docker rm -f testing-app
            fi

            cid=$(docker run -d --name testing-app -v maven-repo:/root/.m2 -p 8080:8001 ${GITHUB_USERNAME}/http-app:snapshot mvn jetty:run) 
            echo "cid=$cid" >> props.env
            echo "IMAGEID=$imageid" >> props.env
            cat props.env
            cip=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' ${cid})
            sleep 30
            sudo docker run --rm rufus/siege-engine -g http://$cip:8001/
            [ $? -ne 0 ] && exit 1
            docker kill ${cid}
            docker rm ${cid}'''.stripIndent())
    }
    publishers {
        downstreamParameterized {
            trigger(testJobName) {
                condition('SUCCESS')
                parameters {
                    predefinedProp('GITHUB_USERNAME', '${GITHUB_USERNAME}')
                    gitRevision(false)
                    propertiesFile('props.env', failTriggerOnMissing = true)
                }
            }
        }
    }
}

job(testJobName) {
    logRotator(-1, 40, -1, -1)
    parameters {
        stringParam('GITHUB_USERNAME', '', 'GITHUB_USERNAME')
        stringParam('version', '', 'version of the application')
        stringParam('IMAGEID', '', 'The docker image to test')
        stringParam('cid', '', 'The container ID')
    }
    steps {
        shell('''\
                cid=$(docker ps --filter="name=testing-app" -q -a)
                if [ ! -z "$cid" ]
                then
                    docker rm -f testing-app
                fi
                testing_cid=$(docker run -d -v maven-repo:/root/.m2 --name testing-app -p 8080:8079  $IMAGEID mvn jetty:run)
                sleep 30
                echo "testing_cid=$testing_cid" > props.env'''.stripIndent())
        environmentVariables {
            propertiesFile('props.env')
        }
        shell('''\
                cip=$(docker inspect --format '{{ .NetworkSettings.IPAddress }}' ${testing_cid})
                docker run --rm rufus/siege-engine  -b -t60S http://$cip:8079/ > output 2>&1'''.stripIndent())
        shell('''\
                avail=$(cat output | grep Availability | awk '{print $2}')
                echo $avail
                # shell uses = to compare strings, bash ==
                if [ "$avail" = "100.00" ]
                then
	                echo "Availability high enough"
	                docker tag -f $IMAGEID ${GITHUB_USERNAME}/http-app:stable
	                exit 0
                else
	                echo "Availability too low"
	                exit 1
                fi'''.stripIndent())

    }
    publishers {
        downstreamParameterized {
            trigger(releaseJobName) {
                condition('SUCCESS')
                parameters {
                    predefinedProp('VERSION', '${version}')
                    predefinedProp('GITHUB_USERNAME', '${GITHUB_USERNAME}')
                }
            }
        }
    }
}

job(releaseJobName) {
    logRotator(-1, 5, -1, -1)
    parameters {
        stringParam('GITHUB_USERNAME', '', 'GITHUB_USERNAME')
        stringParam('VERSION', '', 'version of the application')
    }
    steps {
        shell('''\
                docker tag -f ${GITHUB_USERNAME}/http-app:stable ${GITHUB_USERNAME}/http-app:latest
                docker tag -f ${GITHUB_USERNAME}/http-app:stable ${GITHUB_USERNAME}/http-app:$VERSION
                # no git here yet
                # sudo docker tag http-app/http-app:$(git describe)
                cid=$(docker ps --filter="name=deploy-app" -q -a)
                if [ ! -z "$cid" ]
                then
                    docker rm -f deploy-app
                fi
                docker run -d --name deploy-app -v maven-repo:/root/.m2 -p 8080:8000 ${GITHUB_USERNAME}/http-app:latest'''.stripIndent())
        shell('''\
                docker ps |grep ${GITHUB_USERNAME}/http-app
                docker images |grep ${GITHUB_USERNAME}/http-app'''.stripIndent())
    }
}

listView(viewName) {
    description("All ${projectName} project related jobs")
    jobs {
        regex(".*-${projectName}.*")
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

buildPipelineView(pipelineName) {
    title("Project ${projectName} CI Pipeline")
    displayedBuilds(50)
    selectedJob("${buildJobName}")
    alwaysAllowManualTrigger()
    showPipelineParametersInHeaders()
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(60)
}