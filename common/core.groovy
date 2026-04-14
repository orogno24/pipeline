import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def jsonEscape(val) {
    if (val == null) return ""
    return val
        .toString()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

def createRedmineIssue(String status = "시작") {
    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def commitSha = sh(
            script: 'git rev-parse HEAD',
            returnStdout: true
        ).trim()

        def version = env.APP_VERSION ?: 'unknown'
        def subject = "[Jenkins] ${env.JOB_NAME} - ${env.GIT_BRANCH} - ${version}"
        def description = """배포 시작

프로젝트: ${env.JOB_NAME}
브랜치: ${env.GIT_BRANCH}
태그: ${version}
커밋: ${commitSha}
빌드 번호: ${env.BUILD_NUMBER}
상태: ${status}
빌드 URL: ${env.BUILD_URL ?: ''}
"""

        def payload = [
            issue: [
                project_id : env.REDMINE_PIPELINE_PROJECT_ID ?: "jt-genie-lam",
                tracker_id : (env.REDMINE_PIPELINE_TRACKER_ID ?: "4"),
                subject    : subject,
                description: description,
                status_id  : 13,
                done_ratio : 0
            ]
        ]

        writeFile(
            file: 'redmine_issue.json',
            text: JsonOutput.prettyPrint(JsonOutput.toJson(payload))
        )

        def httpCode = sh(
            script: '''
                set -e
                curl -sS -o redmine_create_response.json -w "%{http_code}" \
                  -X POST "$REDMINE_URL/issues.json" \
                  -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
                  -H "Content-Type: application/json" \
                  --data @redmine_issue.json
            ''',
            returnStdout: true
        ).trim()

        def responseText = readFile('redmine_create_response.json').trim()
        echo "Redmine create HTTP code: ${httpCode}"
        echo "Redmine create response: ${responseText}"

        if (!(httpCode in ['200', '201'])) {
            error("Redmine 이슈 생성 실패: HTTP ${httpCode}, response=${responseText}")
        }

        def parsed
        try {
            parsed = new JsonSlurperClassic().parseText(responseText)
        } catch (err) {
            error("Redmine create 응답 JSON 파싱 실패: ${err}\nresponse=${responseText}")
        }

        def issueId = parsed?.issue?.id?.toString()
        if (!issueId) {
            error("Redmine 이슈 생성 실패: issue.id를 찾지 못했습니다. response=${responseText}")
        }

        env.REDMINE_ISSUE_ID = issueId
        echo "Redmine issue created: ${env.REDMINE_ISSUE_ID}"
    }
}

def updateRedmineIssue(String note, Integer doneRatio = null, Integer statusId = null) {
    if (!env.REDMINE_ISSUE_ID?.trim()) {
        echo "REDMINE_ISSUE_ID 가 없어 Redmine 업데이트를 건너뜁니다."
        return
    }

    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def issueMap = [
            notes: note
        ]

        if (doneRatio != null) {
            issueMap.done_ratio = doneRatio
        }

        if (statusId != null) {
            issueMap.status_id = statusId
        }

        def payload = [issue: issueMap]

        writeFile(
            file: 'redmine_update.json',
            text: JsonOutput.prettyPrint(JsonOutput.toJson(payload))
        )

        def httpCode = sh(
            script: '''
                set +e
                curl -sS -o redmine_update_response.txt -w "%{http_code}" \
                  -X PUT "$REDMINE_URL/issues/$REDMINE_ISSUE_ID.json" \
                  -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
                  -H "Content-Type: application/json" \
                  --data @redmine_update.json
            ''',
            returnStdout: true
        ).trim()

        def responseText = readFile('redmine_update_response.txt').trim()
        echo "Redmine update target issue: ${env.REDMINE_ISSUE_ID}"
        echo "Redmine update HTTP code: ${httpCode}"
        echo "Redmine update response: ${responseText}"

        if (!(httpCode in ['200', '204'])) {
            error("Redmine 이슈 업데이트 실패: issue=${env.REDMINE_ISSUE_ID}, HTTP ${httpCode}, response=${responseText}")
        }
    }
}

def updateTransferIssue(String note, Integer doneRatio = null, Integer statusId = null) {
    if (!env.REDMINE_TRANSFER_ISSUE_ID?.trim()) {
        echo "REDMINE_TRANSFER_ISSUE_ID 가 없어 Transfer Redmine 업데이트를 건너뜁니다."
        return
    }

    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def issueMap = [
            notes: note
        ]

        if (doneRatio != null) {
            issueMap.done_ratio = doneRatio
        }

        if (statusId != null) {
            issueMap.status_id = statusId
        }

        def payload = [issue: issueMap]

        writeFile(
            file: 'redmine_transfer_update.json',
            text: JsonOutput.prettyPrint(JsonOutput.toJson(payload))
        )

        def httpCode = sh(
            script: '''
                set +e
                curl -sS -o redmine_transfer_update_response.txt -w "%{http_code}" \
                  -X PUT "$REDMINE_URL/issues/$REDMINE_TRANSFER_ISSUE_ID.json" \
                  -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
                  -H "Content-Type: application/json" \
                  --data @redmine_transfer_update.json
            ''',
            returnStdout: true
        ).trim()

        def responseText = readFile('redmine_transfer_update_response.txt').trim()
        echo "Redmine transfer update target issue: ${env.REDMINE_TRANSFER_ISSUE_ID}"
        echo "Redmine transfer update HTTP code: ${httpCode}"
        echo "Redmine transfer update response: ${responseText}"

        if (!(httpCode in ['200', '204'])) {
            error("Transfer 이슈 업데이트 실패: issue=${env.REDMINE_TRANSFER_ISSUE_ID}, HTTP ${httpCode}, response=${responseText}")
        }
    }
}

def markStage(String stageName, String result, Integer doneRatio) {
    def msg = """[${stageName}] ${result}
- Job: ${env.JOB_NAME}
- Build: #${env.BUILD_NUMBER}
- Branch: ${env.GIT_BRANCH}
- Version: ${env.APP_VERSION ?: 'unknown'}
- URL: ${env.BUILD_URL ?: ''}
"""
    updateRedmineIssue(msg, doneRatio)
}

def createOrUpdateStartIssue() {
    createRedmineIssue("시작")
    updateRedmineIssue(
        """[Pipeline] 시작
- Job: ${env.JOB_NAME}
- Build: #${env.BUILD_NUMBER}
- Branch: ${env.GIT_BRANCH}
- Version: ${env.APP_VERSION ?: 'unknown'}
- URL: ${env.BUILD_URL ?: ''}
""",
        5
    )
}

def runStageWithRedmine(String stageName, Integer startRatio, Integer successRatio, Closure body) {
    markStage(stageName, '시작', startRatio)
    try {
        body()
        markStage(stageName, '성공', successRatio)
    } catch (err) {
        markStage(stageName, "실패 - ${err}", startRatio)
        throw err
    }
}

def checkout() {
    stage('소스코드 체크아웃') {
        try {
            echo("===============[소스코드 체크아웃] 단계 시작===============")

            git(
                url: env.GIT_URL,
                branch: env.GIT_BRANCH,
                credentialsId: env.GIT_CREDENTIALS_ID
            )

            env.APP_VERSION = sh(
                script: 'git rev-parse --short HEAD',
                returnStdout: true
            ).trim()

            echo("Checked out branch: ${env.GIT_BRANCH}")
            echo("App Version: ${env.APP_VERSION}")
            echo("===============[소스코드 체크아웃] 단계 종료===============")
        } catch (err) {
            echo("[소스코드 체크아웃] 실패: ${err}")
            error("소스코드 체크아웃 단계에서 오류 발생")
        }
    }
}

def build() {
    stage('소스코드 빌드') {
        runStageWithRedmine('소스코드 빌드', 25, 40) {
            echo("===============[소스코드 빌드] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleBuild()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenBuild()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo("===============[소스코드 빌드] 단계 종료===============")
        }
    }
}

def test() {
    stage('테스트') {
        runStageWithRedmine('테스트', 45, 55) {
            echo("===============[테스트] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleTest()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenTest()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo("===============[테스트] 단계 종료===============")
        }
    }
}

def gradleTest() {
    stage('Gradle 테스트') {
        echo("===============[Gradle 테스트] 단계 시작===============")

        try {
            sh('chmod +x ./gradlew')
            sh('./gradlew test')
            echo('JUnit Test 성공적으로 실행 완료')
        } catch (err) {
            echo("JUnit Test 실패: ${err}")
            throw err
        } finally {
            sh('ls -al build/test-results/test || echo "리포트 디렉토리 없음"')

            def reportsExist = fileExists('build/test-results/test')
            if (reportsExist) {
                junit('**/build/test-results/test/*.xml')
            } else {
                echo 'JUnit 리포트 파일이 존재하지 않아 junit 스텝을 건너뜁니다.'
            }
        }

        echo("===============[Gradle 테스트] 단계 종료===============")
    }
}

def gradleBuild() {
    stage('Gradle 빌드') {
        echo("===============[Gradle 빌드] 단계 시작===============")

        retry(3) {
            try {
                sh 'java -version'
                sh 'chmod +x ./gradlew'
                sh './gradlew -v'
                sh './gradlew clean build -x test --no-daemon'
            } catch (err) {
                if (err.getMessage().contains('not found') || err.getMessage().contains('Permission denied')) {
                    echo("Gradle 실행 자체 실패")
                } else {
                    echo("Gradle 빌드 실패 (컴파일/빌드 오류)")
                }
                throw err
            }
        }

        echo("===============[Gradle 빌드] 단계 종료===============")
    }
}

def mavenBuild() {
    stage('Maven 빌드') {
        echo("===============[Maven 빌드] 단계 시작===============")

        retry(3) {
            try {
                sh 'java -version'
                sh 'mvn -v'
                sh 'mvn clean install -DskipTests'
            } catch (err) {
                if (err.getMessage().contains('not found') || err.getMessage().contains('Permission denied')) {
                    echo("Maven 실행 자체 실패")
                } else {
                    echo("Maven 빌드 실패 (컴파일/빌드 오류)")
                }
                throw err
            }
        }

        echo("===============[Maven 빌드] 단계 종료===============")
    }
}

def sonarAnalysis() {
    stage('정적 코드 분석') {
        markStage('정적 코드 분석', '시작', 60)
        try {
            echo("===============[정적 코드 분석] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleSonarAnalysis()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenSonarAnalysis()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            markStage('정적 코드 분석', '성공', 65)
            echo("===============[정적 코드 분석] 단계 종료===============")
        } catch (err) {
            markStage('정적 코드 분석', "실패 - ${err}", 60)
            echo("[정적 코드 분석] 실패: ${err}")
        }
    }
}

def gradleSonarAnalysis() {
    stage('Gradle 정적 코드 분석') {
        try {
            withSonarQubeEnv('sonarqube') {
                sh 'chmod +x ./gradlew'
                sh """
                    ./gradlew sonar --no-daemon \
                        -Dsonar.projectKey=${env.SONAR_PROJECT_KEY} \
                        -Dsonar.projectName='${env.SONAR_PROJECT_NAME}' \
                        -Dsonar.host.url=${env.SONAR_HOST_URL} \
                        -Dsonar.token=${SONAR_AUTH_TOKEN} \
                        -Dsonar.java.binaries=./build/classes/java/main
                """
            }
        } catch (err) {
            echo("[Gradle 정적 코드 분석] 실패: ${err}")
            throw err
        }
    }
}

def sonarAnalysisResult() {
    stage('소나큐브 분석 결과') {
        markStage('소나큐브 분석 결과', '시작', 66)
        try {
            echo("===============[소나큐브 분석 결과] 단계 시작===============")

            def reportTaskExists = fileExists('build/sonar/report-task.txt')
            if (!reportTaskExists) {
                def mavenReportTaskExists = fileExists('target/sonar/report-task.txt')

                if (!mavenReportTaskExists) {
                    echo("SonarQube 분석 결과 파일(report-task.txt)이 존재하지 않습니다. 품질 게이트 검사를 건너뜁니다.")
                    updateRedmineIssue("[소나큐브 분석 결과] report-task.txt 없음. 품질 게이트 생략", 68)
                    echo("===============[소나큐브 분석 결과] 단계 종료===============")
                    return
                }
            }

            withSonarQubeEnv('sonarqube') {
                timeout(time: 10, unit: 'MINUTES') {
                    try {
                        def qg = waitForQualityGate()
                        echo("소나큐브 품질 게이트 검사 결과: ${qg.status}")

                        if (qg.status != 'OK') {
                            updateRedmineIssue("[소나큐브 분석 결과] 품질 게이트 실패: ${qg.status}", 68)
                            echo("경고: 품질 게이트 검사에 실패했지만 파이프라인은 계속 진행됩니다.")
                        } else {
                            updateRedmineIssue("[소나큐브 분석 결과] 품질 게이트 통과", 70)
                            echo("소나큐브 품질 게이트 검사 통과")
                        }
                    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                        updateRedmineIssue("[소나큐브 분석 결과] 품질 게이트 대기 타임아웃", 68)
                        echo("소나큐브 분석 결과 대기 중 타임아웃이 발생했습니다.")
                    } catch (Exception e) {
                        updateRedmineIssue("[소나큐브 분석 결과] 품질 게이트 검사 오류: ${e.message}", 68)
                        echo("소나큐브 품질 게이트 검사 중 오류 발생: ${e.message}")
                    }
                }
            }

            markStage('소나큐브 분석 결과', '성공', 70)
            echo("===============[소나큐브 분석 결과] 단계 종료===============")
        } catch (err) {
            markStage('소나큐브 분석 결과', "실패 - ${err}", 66)
            echo("[소나큐브 분석 결과] 실패: ${err}")
            echo("경고: 소나큐브 분석 결과 단계에서 오류가 발생했지만 파이프라인은 계속 진행됩니다.")
        }
    }
}

def dockerBuild() {
    stage('이미지 빌드') {
        runStageWithRedmine('이미지 빌드', 72, 82) {
            echo("===============[이미지 빌드] 단계 시작===============")

            container('docker-dind') {
                sh '''
                    docker build -t "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" .
                    docker tag "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:latest"
                '''
            }

            echo("===============[이미지 빌드] 단계 종료===============")
        }
    }
}

def dockerPush() {
    stage('이미지 푸시') {
        runStageWithRedmine('이미지 푸시', 83, 90) {
            echo("===============[이미지 푸시] 단계 시작===============")

            container('docker-dind') {
                withCredentials([usernamePassword(
                    credentialsId: env.DOCKER_REGISTRY_CREDENTIALS_ID,
                    usernameVariable: 'HARBOR_USER',
                    passwordVariable: 'HARBOR_PASS'
                )]) {
                    sh '''
                        set -e
                        echo "$HARBOR_PASS" | docker login "$DOCKER_REGISTRY_URL" -u "$HARBOR_USER" --password-stdin
                        docker push "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION"
                    '''
                }
            }

            echo("===============[이미지 푸시] 단계 종료===============")
        }
    }
}

def gitOpsDeploy() {
    stage('GitOps 배포') {
        runStageWithRedmine('GitOps 배포', 95, 100) {
            echo("===============[GitOps 배포] 단계 시작===============")

            git(
                url: env.GITOPS_URL,
                branch: env.GITOPS_BRANCH,
                credentialsId: env.GITOPS_CREDENTIALS_ID
            )

            dir(env.GITOPS_DIR) {
                withCredentials([usernamePassword(
                    credentialsId: env.GITOPS_CREDENTIALS_ID,
                    passwordVariable: 'GITOPS_PASSWORD',
                    usernameVariable: 'GITOPS_USERNAME'
                )]) {

                    def currentVersion = sh(
                        script: "grep 'newTag:' kustomization.yaml | awk -F ':' '{print \$2}' | head -n 1",
                        returnStdout: true
                    ).trim().replaceAll('"', '')

                    echo("현재 GitOps 이미지 태그: ${currentVersion}")
                    echo("배포할 이미지 태그(APP_VERSION): ${env.APP_VERSION}")

                    def featureBranch = "${env.GITOPS_CONTAINER_NAME}-${env.GIT_BRANCH}-${env.APP_VERSION}"

                    if (currentVersion != env.APP_VERSION) {
                        echo("이미지 태그가 달라 GitOps 업데이트를 진행합니다.")

                        sh """
                            git config --global user.email "jenkins@okestro.com"
                            git config --global user.name "jenkins"
                            git checkout -b ${featureBranch}

                            sed -i 's|newTag:.*|newTag: "${env.APP_VERSION}"|g' kustomization.yaml

                            git add kustomization.yaml
                            git commit -m "[${env.GIT_BRANCH}] Update image tag to ${env.APP_VERSION}"
                            git push http://${GITOPS_USERNAME}:${GITOPS_PASSWORD}@${GITOPS_PATH} ${featureBranch}
                        """

                        def giteaApiUrl = env.GITOPS_API_URL

                        def prNumber = sh(
                            script: """
                                curl -s -u ${GITOPS_USERNAME}:${GITOPS_PASSWORD} \\
                                    -X POST "${giteaApiUrl}/pulls" \\
                                    -H "Content-Type: application/json" \\
                                    -d '{
                                        "head": "${featureBranch}",
                                        "base": "${env.GITOPS_BRANCH}",
                                        "title": "[${env.GIT_BRANCH}] Update image tag to ${env.APP_VERSION}",
                                        "body": "Auto-created by Jenkins webhook\\nBranch: ${env.GIT_BRANCH}\\nImage tag: ${env.APP_VERSION}"
                                    }' \
                                | sed -n 's/.*"number":[ ]*\\([0-9]*\\).*/\\1/p'
                            """,
                            returnStdout: true
                        ).trim()

                        env.GITOPS_PR_NUMBER = prNumber ?: ''

                        echo "생성된 PR 번호: ${prNumber}"

                        updateRedmineIssue(
                            """[GitOps 배포] PR 생성 완료
- PR 번호: ${prNumber}
- Feature Branch: ${featureBranch}
""",
                            97
                        )

                        def merged = false
                        def retryCnt = 0
                        while (!merged && retryCnt < 10) {
                            def merge = sh(
                                script: """
                                    curl -s -u ${GITOPS_USERNAME}:${GITOPS_PASSWORD} \\
                                        -X POST "${giteaApiUrl}/pulls/${prNumber}/merge" \\
                                        -H "Content-Type: application/json" \\
                                        -d '{
                                            "do": "merge",
                                            "merge_method": "merge",
                                            "commit_title": "[${env.GIT_BRANCH}] Auto-merged by Jenkins",
                                            "commit_message": "Update image tag to ${env.APP_VERSION}"
                                        }'
                                """,
                                returnStdout: true
                            ).trim()

                            echo("Merge response : ${merge}")
                            if (merge.contains("try")) {
                                echo("Gitea Merge Failed, Retry")
                                retryCnt++
                                sleep 5
                            } else {
                                merged = true
                            }
                        }

                        if (merged) {
                            sleep 5
                            sh("git checkout ${env.GITOPS_BRANCH}")
                            sh("git push http://${GITOPS_USERNAME}:${GITOPS_PASSWORD}@${GITOPS_PATH} --delete ${featureBranch}")

                            updateRedmineIssue(
                                """[GitOps 배포] Merge 성공 및 feature branch 삭제 완료
- PR 번호: ${prNumber}
- 배포 태그: ${env.APP_VERSION}
""",
                                100
                            )

                            echo("Gitea Merge Success, Feature Branch Deleted")
                        } else {
                            updateRedmineIssue(
                                """[GitOps 배포] Merge 실패
- PR 번호: ${prNumber}
- Feature Branch: ${featureBranch}
""",
                                97
                            )
                            error("Gitea Merge Failed")
                        }
                    } else {
                        env.GITOPS_PR_NUMBER = ''
                        updateRedmineIssue(
                            """[GitOps 배포] 현재 GitOps 태그와 동일하여 업데이트 생략
- Current Tag: ${currentVersion}
""",
                            100
                        )
                        echo("현재 GitOps 태그와 동일하므로 업데이트하지 않습니다.")
                    }
                }
            }

            echo("===============[GitOps 배포] 단계 종료===============")
        }
    }
}

def createImageTransferIssue() {
    stage('이미지 이관 요청 생성') {
        updateRedmineIssue(
            """[이미지 이관 요청] 생성 시작
- Source Image: ${env.DOCKER_IMAGE_URL}:${env.APP_VERSION}
- Target Image: ${env.TARGET_DOCKER_IMAGE_URL}:${env.APP_VERSION}
- GitOps PR: ${env.GITOPS_PR_NUMBER ?: '없음'}
""",
            100
        )

        withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
            def subject = "[이미지 이관 요청] ${env.JOB_NAME} - ${env.GIT_BRANCH} - ${env.APP_VERSION}"

            def description = """이미지 이관 요청

아래 정보는 Jenkins가 자동 생성했습니다.
관리자가 상태를 승인 상태로 변경하면 별도 Jenkins Job이 실행되어 이미지를 다른 Harbor로 이관합니다.

[REQUEST_INFO]
REQUEST_TYPE=IMAGE_TRANSFER
SOURCE_REGISTRY=${env.DOCKER_REGISTRY_URL}
SOURCE_IMAGE=${env.DOCKER_IMAGE_URL}:${env.APP_VERSION}
SOURCE_REPOSITORY=${env.DOCKER_IMAGE_NAME}
TARGET_REGISTRY=${env.TARGET_DOCKER_REGISTRY_URL}
TARGET_IMAGE=${env.TARGET_DOCKER_IMAGE_URL}:${env.APP_VERSION}
TARGET_REPOSITORY=${env.TARGET_DOCKER_IMAGE_NAME}
IMAGE_TAG=${env.APP_VERSION}
SERVICE_NAME=${env.GITOPS_CONTAINER_NAME}
GIT_BRANCH=${env.GIT_BRANCH}
GITOPS_PR_NUMBER=${env.GITOPS_PR_NUMBER ?: ''}
ORIGIN_PIPELINE_ISSUE_ID=${env.REDMINE_ISSUE_ID ?: ''}
ORIGIN_JOB_NAME=${env.JOB_NAME ?: ''}
ORIGIN_BUILD_NUMBER=${env.BUILD_NUMBER ?: ''}
ORIGIN_BUILD_URL=${env.BUILD_URL ?: ''}
[/REQUEST_INFO]

요약
- Source Harbor: ${env.DOCKER_REGISTRY_URL}
- Source Image: ${env.DOCKER_IMAGE_URL}:${env.APP_VERSION}
- Target Harbor: ${env.TARGET_DOCKER_REGISTRY_URL}
- Target Image: ${env.TARGET_DOCKER_IMAGE_URL}:${env.APP_VERSION}
- GitOps PR: ${env.GITOPS_PR_NUMBER ?: '없음'}
"""

            def payload = [
                issue: [
                    project_id : env.REDMINE_TRANSFER_PROJECT_ID ?: "jt-genie-lam",
                    tracker_id : env.REDMINE_TRANSFER_TRACKER_ID ?: "5",
                    subject    : subject,
                    description: description,
                    status_id  : (env.REDMINE_TRANSFER_STATUS_ID_REQUESTED ?: "1"),
                    done_ratio : 0
                ]
            ]

            writeFile(
                file: 'redmine_transfer_issue.json',
                text: JsonOutput.prettyPrint(JsonOutput.toJson(payload))
            )

            def httpCode = sh(
                script: '''
                    set -e
                    curl -sS -o redmine_transfer_create_response.json -w "%{http_code}" \
                      -X POST "$REDMINE_URL/issues.json" \
                      -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
                      -H "Content-Type: application/json" \
                      --data @redmine_transfer_issue.json
                ''',
                returnStdout: true
            ).trim()

            def responseText = readFile('redmine_transfer_create_response.json').trim()
            echo "Redmine transfer create HTTP code: ${httpCode}"
            echo "Redmine transfer create response: ${responseText}"

            if (!(httpCode in ['200', '201'])) {
                error("Redmine transfer 이슈 생성 실패: HTTP ${httpCode}, response=${responseText}")
            }

            def parsed
            try {
                parsed = new JsonSlurperClassic().parseText(responseText)
            } catch (err) {
                error("Redmine transfer create 응답 JSON 파싱 실패: ${err}\nresponse=${responseText}")
            }

            def issueId = parsed?.issue?.id?.toString()
            if (!issueId) {
                error("Redmine transfer 이슈 생성 실패: issue.id를 찾지 못했습니다. response=${responseText}")
            }

            env.REDMINE_TRANSFER_ISSUE_ID = issueId
            echo "Redmine transfer issue created: ${env.REDMINE_TRANSFER_ISSUE_ID}"

            updateRedmineIssue(
                """[이미지 이관 요청] 생성 완료
- Transfer Issue ID: ${env.REDMINE_TRANSFER_ISSUE_ID}
- Source Image: ${env.DOCKER_IMAGE_URL}:${env.APP_VERSION}
- Target Image: ${env.TARGET_DOCKER_IMAGE_URL}:${env.APP_VERSION}
""",
                100
            )
        }
    }
}

def readRedmineIssue(String issueId) {
    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def httpCode = sh(
            script: """
                set +e
                curl -sS -o redmine_issue_detail.json -w "%{http_code}" \
                  -X GET "$REDMINE_URL/issues/${issueId}.json?include=journals" \
                  -H "X-Redmine-API-Key: $REDMINE_API_KEY"
            """,
            returnStdout: true
        ).trim()

        def responseText = readFile('redmine_issue_detail.json').trim()
        echo "Redmine issue read HTTP code: ${httpCode}"
        echo "Redmine issue read response: ${responseText}"

        if (!(httpCode in ['200'])) {
            error("Redmine issue 조회 실패: issue=${issueId}, HTTP ${httpCode}, response=${responseText}")
        }

        try {
            return new JsonSlurperClassic().parseText(responseText)
        } catch (err) {
            error("Redmine issue 조회 응답 JSON 파싱 실패: ${err}\nresponse=${responseText}")
        }
    }
}

def parseTransferRequest(String description) {
    if (!description?.trim()) {
        error("Redmine description 이 비어 있습니다.")
    }

    def beginMarker = "[REQUEST_INFO]"
    def endMarker = "[/REQUEST_INFO]"

    def startIdx = description.indexOf(beginMarker)
    def endIdx = description.indexOf(endMarker)

    if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
        error("REQUEST_INFO 블록을 찾지 못했습니다.")
    }

    def block = description.substring(startIdx + beginMarker.length(), endIdx).trim()
    def lines = block.readLines()

    def result = [:]
    lines.each { line ->
        def trimmed = line?.trim()
        if (!trimmed || !trimmed.contains('=')) {
            return
        }
        def idx = trimmed.indexOf('=')
        def key = trimmed.substring(0, idx).trim()
        def value = trimmed.substring(idx + 1).trim()
        result[key] = value
    }

    if (!result.REQUEST_TYPE || result.REQUEST_TYPE != 'IMAGE_TRANSFER') {
        error("REQUEST_TYPE 이 IMAGE_TRANSFER 가 아닙니다. value=${result.REQUEST_TYPE}")
    }

    return result
}

def loadTransferIssueFromRedmine(String issueId) {
    def parsed = readRedmineIssue(issueId)
    def issue = parsed?.issue

    if (!issue) {
        error("Redmine issue 정보가 없습니다. issueId=${issueId}")
    }

    env.REDMINE_TRANSFER_ISSUE_ID = issue.id?.toString() ?: issueId
    env.REDMINE_TRANSFER_SUBJECT = issue.subject?.toString() ?: ''
    env.REDMINE_TRANSFER_DESCRIPTION = issue.description?.toString() ?: ''
    env.REDMINE_TRANSFER_STATUS_NAME = issue.status?.name?.toString() ?: ''
    env.REDMINE_TRANSFER_STATUS_ID = issue.status?.id?.toString() ?: ''

    def req = parseTransferRequest(env.REDMINE_TRANSFER_DESCRIPTION)

    env.TRANSFER_REQUEST_TYPE = req.REQUEST_TYPE ?: ''
    env.TRANSFER_SOURCE_REGISTRY = req.SOURCE_REGISTRY ?: ''
    env.TRANSFER_SOURCE_IMAGE = req.SOURCE_IMAGE ?: ''
    env.TRANSFER_SOURCE_REPOSITORY = req.SOURCE_REPOSITORY ?: ''
    env.TRANSFER_TARGET_REGISTRY = req.TARGET_REGISTRY ?: ''
    env.TRANSFER_TARGET_IMAGE = req.TARGET_IMAGE ?: ''
    env.TRANSFER_TARGET_REPOSITORY = req.TARGET_REPOSITORY ?: ''
    env.TRANSFER_IMAGE_TAG = req.IMAGE_TAG ?: ''
    env.TRANSFER_SERVICE_NAME = req.SERVICE_NAME ?: ''
    env.TRANSFER_GIT_BRANCH = req.GIT_BRANCH ?: ''
    env.TRANSFER_GITOPS_PR_NUMBER = req.GITOPS_PR_NUMBER ?: ''
    env.TRANSFER_ORIGIN_PIPELINE_ISSUE_ID = req.ORIGIN_PIPELINE_ISSUE_ID ?: ''
    env.TRANSFER_ORIGIN_JOB_NAME = req.ORIGIN_JOB_NAME ?: ''
    env.TRANSFER_ORIGIN_BUILD_NUMBER = req.ORIGIN_BUILD_NUMBER ?: ''
    env.TRANSFER_ORIGIN_BUILD_URL = req.ORIGIN_BUILD_URL ?: ''

    echo "Transfer issue loaded: ${env.REDMINE_TRANSFER_ISSUE_ID}"
    echo "TRANSFER_SOURCE_IMAGE=${env.TRANSFER_SOURCE_IMAGE}"
    echo "TRANSFER_TARGET_IMAGE=${env.TRANSFER_TARGET_IMAGE}"
    echo "TRANSFER_IMAGE_TAG=${env.TRANSFER_IMAGE_TAG}"
}

def dockerTransferImage() {
    stage('이미지 이관') {
        if (!env.TRANSFER_SOURCE_IMAGE?.trim()) {
            error("TRANSFER_SOURCE_IMAGE 값이 없습니다.")
        }
        if (!env.TRANSFER_TARGET_IMAGE?.trim()) {
            error("TRANSFER_TARGET_IMAGE 값이 없습니다.")
        }

        updateTransferIssue(
            """[이미지 이관] 시작
- Source Image: ${env.TRANSFER_SOURCE_IMAGE}
- Target Image: ${env.TRANSFER_TARGET_IMAGE}
- Tag: ${env.TRANSFER_IMAGE_TAG}
- Trigger Build: #${env.BUILD_NUMBER}
- Trigger URL: ${env.BUILD_URL ?: ''}
""",
            10,
            (env.REDMINE_TRANSFER_STATUS_ID_IN_PROGRESS ?: "3") as Integer
        )

        container('docker-dind') {
            withCredentials([
                usernamePassword(
                    credentialsId: env.DOCKER_REGISTRY_CREDENTIALS_ID,
                    usernameVariable: 'SRC_HARBOR_USER',
                    passwordVariable: 'SRC_HARBOR_PASS'
                ),
                usernamePassword(
                    credentialsId: env.TARGET_DOCKER_REGISTRY_CREDENTIALS_ID,
                    usernameVariable: 'DST_HARBOR_USER',
                    passwordVariable: 'DST_HARBOR_PASS'
                )
            ]) {
                sh '''
                    set -eux

                    echo "$SRC_HARBOR_PASS" | docker login "$TRANSFER_SOURCE_REGISTRY" -u "$SRC_HARBOR_USER" --password-stdin
                    echo "$DST_HARBOR_PASS" | docker login "$TRANSFER_TARGET_REGISTRY" -u "$DST_HARBOR_USER" --password-stdin

                    docker pull "$TRANSFER_SOURCE_IMAGE"
                    docker tag "$TRANSFER_SOURCE_IMAGE" "$TRANSFER_TARGET_IMAGE"
                    docker push "$TRANSFER_TARGET_IMAGE"

                    docker image rm "$TRANSFER_SOURCE_IMAGE" || true
                    docker image rm "$TRANSFER_TARGET_IMAGE" || true
                '''
            }
        }

        updateTransferIssue(
            """[이미지 이관] 완료
- Source Image: ${env.TRANSFER_SOURCE_IMAGE}
- Target Image: ${env.TRANSFER_TARGET_IMAGE}
- Tag: ${env.TRANSFER_IMAGE_TAG}
- Trigger Build: #${env.BUILD_NUMBER}
- Trigger URL: ${env.BUILD_URL ?: ''}
""",
            100,
            (env.REDMINE_TRANSFER_STATUS_ID_DONE ?: "4") as Integer
        )
    }
}

def transferSourceArchiveToFtc(String sourceDir = 'source', String orgName = '', String appName = 'app') {
    stage('소스코드 FTC 전송') {
        runStageWithRedmine('소스코드 FTC 전송', 96, 100) {
            echo("===============[소스코드 FTC 전송] 단계 시작===============")

            sh """
                set -e

                INPUT_ORG_NAME="${orgName}"
                APP_NAME="${appName}"

                REMOTE_URL=\$(git -C "${sourceDir}" remote get-url origin || true)
                AUTO_ORG_NAME=\$(printf '%s\n' "\${REMOTE_URL}" | sed -E 's#.+[:/]([^/]+)/[^/]+(\\.git)?#\\1#' || true)

                if [ -n "\${INPUT_ORG_NAME}" ]; then
                    ORG_NAME="\${INPUT_ORG_NAME}"
                elif [ -n "\${AUTO_ORG_NAME}" ] && [ "\${AUTO_ORG_NAME}" != "\${REMOTE_URL}" ]; then
                    ORG_NAME="\${AUTO_ORG_NAME}"
                else
                    ORG_NAME="unknown-org"
                fi

                BUILD_DATE=\$(date +%Y%m%d%H%M%S)

                ARCHIVE_NAME="org=\${ORG_NAME}__app=\${APP_NAME}__build=${BUILD_NUMBER}__date=\${BUILD_DATE}.tar.gz"

                echo "=== debug start ==="
                echo "REMOTE_URL=\${REMOTE_URL}"
                echo "ORG_NAME=\${ORG_NAME}"
                echo "APP_NAME=\${APP_NAME}"
                echo "ARCHIVE_NAME=\${ARCHIVE_NAME}"
                echo "=== debug end ==="

                mkdir -p dist

                tar -C "${sourceDir}" \
                    --exclude='.git' \
                    --exclude='dist' \
                    --exclude='.scannerwork' \
                    --exclude='node_modules' \
                    --exclude='target' \
                    --exclude='build' \
                    -czf "dist/\${ARCHIVE_NAME}" .

                cp "dist/\${ARCHIVE_NAME}" /ftc-upload/

                echo "=== archive created ==="
                ls -lh "dist/\${ARCHIVE_NAME}"
                echo "=== archive copied to /ftc-upload ==="
                ls -lh /ftc-upload/"\${ARCHIVE_NAME}"
            """

            echo("===============[소스코드 FTC 전송] 단계 종료===============")
        }
    }
}

return this