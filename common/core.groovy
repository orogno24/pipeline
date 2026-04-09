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

def createRedmineIssue(String status = "START") {
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

        writeFile file: 'redmine_issue.json', text: """{
  "issue": {
    "project_id": "jt-genie-lam",
    "subject": ${groovy.json.JsonOutput.toJson(subject)},
    "description": ${groovy.json.JsonOutput.toJson(description)},
    "done_ratio": 0
  }
}"""

        def issueId = sh(
            script: '''
                set -e
                RESPONSE=$(curl -sS -X POST "$REDMINE_URL/issues.json" \
                  -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
                  -H "Content-Type: application/json" \
                  --data @redmine_issue.json)

                echo "[Redmine API 응답]: $RESPONSE" >&2
                echo "$RESPONSE" | sed -n 's/.*"id":[ ]*\\([0-9][0-9]*\\).*/\\1/p' | head -n 1
            ''',
            returnStdout: true
        ).trim()

        if (!issueId) {
            error("Redmine 이슈 생성 실패: ID를 추출하지 못했습니다.")
        }

        env.REDMINE_ISSUE_ID = issueId
        echo "Redmine 이슈 생성 완료: ${env.REDMINE_ISSUE_ID}"
    }
}

def updateRedmineIssue(String note, Integer doneRatio = null, Integer statusId = null) {
    if (!env.REDMINE_ISSUE_ID?.trim()) {
        echo "REDMINE_ISSUE_ID가 없어 업데이트를 건너뜁니다."
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

        def response = sh(
            script: """
                curl -sS -X PUT "${env.REDMINE_URL}/issues/${env.REDMINE_ISSUE_ID}.json" \
                  -H "X-Redmine-API-Key: ${REDMINE_API_KEY}" \
                  -H "Content-Type: application/json" \
                  -d '${JsonOutput.toJson(payload)}'
            """,
            returnStdout: true
        ).trim()

        echo "Redmine 업데이트 응답: ${response}"
    }
}

def markStage(String stageName, String result, Integer doneRatio) {
    def msg = """[${stageName}] ${result}
- 프로젝트: ${env.JOB_NAME}
- 빌드 번호: #${env.BUILD_NUMBER}
- 브랜치: ${env.GIT_BRANCH}
- 버전: ${env.APP_VERSION ?: 'unknown'}
- URL: ${env.BUILD_URL ?: ''}
"""
    updateRedmineIssue(msg, doneRatio)
}

def createOrUpdateStartIssue() {
    createRedmineIssue("START")
    updateRedmineIssue(
        """파이프라인 시작
- 프로젝트: ${env.JOB_NAME}
- 빌드 번호: #${env.BUILD_NUMBER}
- 브랜치: ${env.GIT_BRANCH}
- 버전: ${env.APP_VERSION ?: 'unknown'}
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
    stage('소스 코드 체크아웃') {
        try {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] 소스 코드 체크아웃 (Git Checkout)"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            git(
                url: env.GIT_URL,
                branch: env.GIT_BRANCH,
                credentialsId: env.GIT_CREDENTIALS_ID
            )

            env.APP_VERSION = sh(
                script: 'git rev-parse --short HEAD',
                returnStdout: true
            ).trim()

            echo "체크아웃 브랜치: ${env.GIT_BRANCH}"
            echo "앱 버전 (커밋 SHA): ${env.APP_VERSION}"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        } catch (err) {
            echo "[소스 코드 체크아웃] 실패: ${err}"
            error("Git 체크아웃 단계에서 오류 발생")
        }
    }
}

def build() {
    stage('프로젝트 빌드') {
        runStageWithRedmine('빌드', 25, 40) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] 프로젝트 빌드 (${env.BUILD_TOOL})"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            if (env.BUILD_TOOL == 'gradle') {
                gradleBuild()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenBuild()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

def test() {
    stage('단위 테스트 실행') {
        runStageWithRedmine('테스트', 45, 55) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] 단위 테스트 실행 (${env.BUILD_TOOL})"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            if (env.BUILD_TOOL == 'gradle') {
                gradleTest()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenTest()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

def gradleTest() {
    stage('Gradle 테스트') {
        echo "▶ Gradle 테스트 시작..."
        try {
            sh('chmod +x ./gradlew')
            sh('./gradlew test')
            echo 'JUnit 테스트 실행 완료'
        } catch (err) {
            echo "JUnit 테스트 실패: ${err}"
            throw err
        } finally {
            sh('ls -al build/test-results/test || echo "리포트 디렉토리 없음"')

            def reportsExist = fileExists('build/test-results/test')
            if (reportsExist) {
                junit('**/build/test-results/test/*.xml')
            } else {
                echo 'JUnit 리포트 파일이 존재하지 않아 결과 수집을 건너뜁니다.'
            }
        }
    }
}

def mavenTest() {
    stage('Maven 테스트') {
        echo "▶ Maven 테스트 시작..."
        try {
            sh('mvn test')
            echo 'JUnit 테스트 실행 완료'
        } catch (err) {
            echo "JUnit 테스트 실패: ${err}"
            throw err
        } finally {
            sh('ls -al target/surefire-reports || echo "리포트 디렉토리 없음"')

            def reportsExist = fileExists('target/surefire-reports')
            if (reportsExist) {
                junit('**/target/surefire-reports/*.xml')
            } else {
                echo 'JUnit 리포트 파일이 존재하지 않아 결과 수집을 건너뜁니다.'
            }
        }
    }
}

def gradleBuild() {
    stage('Gradle 빌드') {
        echo "▶ Gradle 빌드 시작 (최대 3회 재시도)..."
        retry(3) {
            try {
                sh 'java -version'
                sh 'chmod +x ./gradlew'
                sh './gradlew -v'
                sh './gradlew clean build -x test --no-daemon'
            } catch (err) {
                if (err.getMessage().contains('not found') || err.getMessage().contains('Permission denied')) {
                    echo "Gradle 실행 환경 오류"
                } else {
                    echo "Gradle 컴파일/빌드 오류"
                }
                throw err
            }
        }
    }
}

def mavenBuild() {
    stage('Maven 빌드') {
        echo "▶ Maven 빌드 시작 (최대 3회 재시도)..."
        retry(3) {
            try {
                sh 'java -version'
                sh 'mvn -v'
                sh 'mvn clean install -DskipTests'
            } catch (err) {
                if (err.getMessage().contains('not found') || err.getMessage().contains('Permission denied')) {
                    echo "Maven 실행 환경 오류"
                } else {
                    echo "Maven 컴파일/빌드 오류"
                }
                throw err
            }
        }
    }
}

def sonarAnalysis() {
    stage('정적 코드 분석') {
        markStage('코드 분석', '시작', 60)
        try {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] SonarQube 정적 코드 분석"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            if (env.BUILD_TOOL == 'gradle') {
                gradleSonarAnalysis()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenSonarAnalysis()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            markStage('코드 분석', '성공', 65)
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        } catch (err) {
            markStage('코드 분석', "실패 - ${err}", 60)
            echo " [코드 분석] 실패: ${err} (분석 실패해도 빌드는 계속 진행)"
        }
    }
}

def gradleSonarAnalysis() {
    stage('Gradle 분석') {
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
    }
}

def mavenSonarAnalysis() {
    stage('Maven 분석') {
        withSonarQubeEnv('sonarqube') {
            sh 'java -version'
            sh 'mvn -v'
            sh """
                mvn sonar:sonar \
                    -Dsonar.projectKey=${env.SONAR_PROJECT_KEY} \
                    -Dsonar.projectName='${env.SONAR_PROJECT_NAME}' \
                    -Dsonar.host.url=${env.SONAR_HOST_URL} \
                    -Dsonar.token=${SONAR_AUTH_TOKEN}
            """
        }
    }
}

def sonarAnalysisResult() {
    stage('품질 게이트 확인') {
        markStage('품질 게이트', '시작', 66)
        try {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] SonarQube 품질 게이트 결과 확인"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            def reportTaskExists = fileExists('build/sonar/report-task.txt')
            if (!reportTaskExists) {
                def mavenReportTaskExists = fileExists('target/sonar/report-task.txt')

                if (!mavenReportTaskExists) {
                    echo "분석 결과 파일(report-task.txt)이 없습니다. 검사를 건너뜁니다."
                    updateRedmineIssue("[품질 게이트] 분석 파일 없음. 검사 생략", 68)
                    return
                }
            }

            withSonarQubeEnv('sonarqube') {
                timeout(time: 10, unit: 'MINUTES') {
                    try {
                        def qg = waitForQualityGate()
                        echo "품질 게이트 상태: ${qg.status}"

                        if (qg.status != 'OK') {
                            updateRedmineIssue("[품질 게이트] 실패: ${qg.status}", 68)
                            echo "경고: 품질 게이트를 통과하지 못했으나 파이프라인은 진행됩니다."
                        } else {
                            updateRedmineIssue("[품질 게이트] 통과", 70)
                            echo "소나큐브 품질 게이트 통과"
                        }
                    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                        updateRedmineIssue("[품질 게이트] 타임아웃 발생", 68)
                        echo "결과 대기 시간 초과"
                    } catch (Exception e) {
                        updateRedmineIssue("[품질 게이트] 오류: ${e.message}", 68)
                        echo "검사 중 오류 발생: ${e.message}"
                    }
                }
            }

            markStage('품질 게이트', '성공', 70)
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        } catch (err) {
            markStage('품질 게이트', "실패 - ${err}", 66)
            echo "[품질 게이트] 단계 오류 발생 (진행 유지)"
        }
    }
}

def dockerBuild() {
    stage('도커 이미지 빌드') {
        runStageWithRedmine('이미지 빌드', 72, 82) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] Docker 이미지 빌드"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            container('docker-dind') {
                sh '''
                    docker build -t "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" .
                    docker tag "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:latest"
                '''
            }

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

def dockerPush() {
    stage('도커 이미지 푸시') {
        runStageWithRedmine('이미지 푸시', 83, 90) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] Docker Registry 이미지 업로드"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

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

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

def harborImageScan() {
    stage('이미지 취약점 점검') {
        markStage('이미지 점검', '시작', 91)
        try {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] Harbor 보안 스캔 및 결과 확인"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            withCredentials([usernamePassword(
                credentialsId: env.DOCKER_REGISTRY_CREDENTIALS_ID,
                usernameVariable: 'HARBOR_USER',
                passwordVariable: 'HARBOR_PASS'
            )]) {
                sh '''
                    curl -v -k -X POST -u "$HARBOR_USER:$HARBOR_PASS" \
                        "https://$DOCKER_REGISTRY_URL_OPS/api/v2.0/projects/$DOCKER_PROJECT_NAME/repositories/$DOCKER_REPO_NAME/artifacts/$APP_VERSION/scan"
                '''

                def maxWait = 150
                def interval = 10
                def waited = 0
                def vulnResult = '{}'
                def found = false

                while (waited < maxWait) {
                    vulnResult = sh(
                        script: '''
                            curl -sk -u "$HARBOR_USER:$HARBOR_PASS" \
                            "https://$DOCKER_REGISTRY_URL_OPS/api/v2.0/projects/$DOCKER_PROJECT_NAME/repositories/$DOCKER_REPO_NAME/artifacts/$APP_VERSION/additions/vulnerabilities"
                        ''',
                        returnStdout: true
                    ).trim()

                    if (vulnResult != '{}' && vulnResult != '[]' && vulnResult.length() > 5) {
                        found = true
                        break
                    }

                    echo "⏳ 스캔 진행 중... ${interval}초 후 재확인 (경과: ${waited}s)"
                    sleep interval
                    waited += interval
                }

                if (!found) {
                    error("Harbor 스캔 결과를 시간 내에 받지 못했습니다.")
                }

                def parsed = readJSON text: vulnResult
                def reportKey = parsed.keySet().find { it.startsWith('application/vnd.scanner.adapter.vuln.report.harbor') }
                def report = parsed[reportKey]
                def severity = report?.severity ?: 'Unknown'
                def vulnerabilities = report?.vulnerabilities ?: []

                echo "보안 등급: ${severity}"
                def cveList = vulnerabilities.collect { it.id }.join(', ')
                echo "발견된 CVE: ${cveList ?: '없음'}"

                updateRedmineIssue(
                    """[Harbor 이미지 점검] 완료
- 위험도: ${severity}
- CVE 목록: ${cveList ?: '없음'}
""",
                    94
                )

                if (severity.toLowerCase() == 'critical') {
                    echo "심각(Critical) 수준의 취약점이 발견되었습니다!"
                } else {
                    echo "심각한 취약점 없음, 'latest' 태그 푸시를 진행합니다."
                    container('docker-dind') {
                        withCredentials([usernamePassword(
                            credentialsId: env.DOCKER_REGISTRY_CREDENTIALS_ID,
                            usernameVariable: 'HARBOR_USER',
                            passwordVariable: 'HARBOR_PASS'
                        )]) {
                            sh '''
                                echo "$HARBOR_PASS" | docker login "$DOCKER_REGISTRY_URL" -u "$HARBOR_USER" --password-stdin
                                docker push "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:latest"
                            '''
                        }
                    }
                }
            }

            markStage('이미지 점검', '성공', 95)
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        } catch (err) {
            markStage('이미지 점검', "실패 - ${err}", 91)
            error("Harbor 이미지 스캔 단계 오류 발생")
        }
    }
}

def gitOpsDeploy() {
    stage('GitOps 배포 업데이트') {
        runStageWithRedmine('GitOps 배포', 95, 100) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] GitOps 설정 업데이트 (Gitea PR)"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

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

                    echo "현재 배포 태그: ${currentVersion}"
                    echo "신규 배포 태그: ${env.APP_VERSION}"

                    def featureBranch = "${env.GITOPS_CONTAINER_NAME}-${env.GIT_BRANCH}-${env.APP_VERSION}"

                    if (currentVersion != env.APP_VERSION) {
                        echo "태그 변경 감지됨. 업데이트 진행..."

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
                                        "body": "Auto-created by Jenkins\\nBranch: ${env.GIT_BRANCH}\\nTag: ${env.APP_VERSION}"
                                    }' \
                                | sed -n 's/.*"number":[ ]*\\([0-9]*\\).*/\\1/p'
                            """,
                            returnStdout: true
                        ).trim()

                        echo "PR 생성 완료: #${prNumber}"

                        updateRedmineIssue(
                            """[GitOps 배포] PR 생성 완료
- PR 번호: ${prNumber}
- 배포 브랜치: ${featureBranch}
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
                                            "commit_title": "[${env.GIT_BRANCH}] 자동 머지 (Jenkins)",
                                            "commit_message": "Update image tag to ${env.APP_VERSION}"
                                        }'
                                """,
                                returnStdout: true
                            ).trim()

                            echo "머지 시도 결과: ${merge}"
                            if (merge.contains("try")) {
                                echo "Gitea 머지 지연 중... 재시도 (${retryCnt}/10)"
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
                                """[GitOps 배포] 머지 성공 및 임시 브랜치 삭제 완료
- PR 번호: ${prNumber}
- 배포 태그: ${env.APP_VERSION}
""",
                                100
                            )

                            echo "Gitea 머지 성공 및 브랜치 삭제 완료"
                        } else {
                            updateRedmineIssue(
                                """[GitOps 배포] 머지 실패
- PR 번호: ${prNumber}
""",
                                97
                            )
                            error("Gitea 자동 머지 실패")
                        }
                    } else {
                        updateRedmineIssue("[GitOps 배포] 태그가 동일하여 업데이트 생략", 100)
                        echo "현재 태그와 동일하여 업데이트를 생략합니다."
                    }
                }
            }

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

def transferEmptyFileToFtc() {
    stage('FTC 파일 전송 (테스트)') {
        runStageWithRedmine('FTC 전송', 96, 100) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] FTC 테스트용 파일 생성 및 복사"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            sh '''
                set -e
                FILE_NAME="ftc-test-${BUILD_NUMBER}.txt"

                echo "[디버그] 현재 계정 및 권한 정보"
                id && whoami
                ls -ld /ftc-upload

                cat <<EOF > "${FILE_NAME}"
Hello FTC
Build Number: ${BUILD_NUMBER}
Date: $(date)
EOF

                cp "${FILE_NAME}" /ftc-upload/
                echo "전송 확인:"
                ls -l /ftc-upload/"${FILE_NAME}"
            '''

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

def transferSourceArchiveToFtc(String sourceDir = 'source', String orgName = '', String appName = 'app') {
    stage('소스 아카이브 FTC 전송') {
        runStageWithRedmine('소스 아카이브 전송', 96, 100) {
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "▶ [단계 시작] 소스 코드 압축 및 FTC 전송"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

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

                echo "[구성 정보]"
                echo "- 원격 URL: \${REMOTE_URL}"
                echo "- 조직명: \${ORG_NAME}"
                echo "- 앱 명칭: \${APP_NAME}"
                echo "- 파일명: \${ARCHIVE_NAME}"

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

                echo "아카이브 생성 완료 및 /ftc-upload 복사 완료"
                ls -lh /ftc-upload/"\${ARCHIVE_NAME}"
            """

            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        }
    }
}

return this