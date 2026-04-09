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

        def payload = [
            issue: [
                project_id : 'jt-genie-lam',
                subject    : subject,
                description: description,
                done_ratio : 0
            ]
        ]

        def response = sh(
            script: """
                curl -sS -X POST "${env.REDMINE_URL}/issues.json" \
                  -H "X-Redmine-API-Key: ${REDMINE_API_KEY}" \
                  -H "Content-Type: application/json" \
                  -d '${JsonOutput.toJson(payload)}'
            """,
            returnStdout: true
        ).trim()

        echo "Redmine create response: ${response}"

        def parsed = new JsonSlurperClassic().parseText(response)
        if (!parsed?.issue?.id) {
            error("Redmine 이슈 생성 실패: ${response}")
        }

        env.REDMINE_ISSUE_ID = parsed.issue.id.toString()
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

        def response = sh(
            script: """
                curl -sS -X PUT "${env.REDMINE_URL}/issues/${env.REDMINE_ISSUE_ID}.json" \
                  -H "X-Redmine-API-Key: ${REDMINE_API_KEY}" \
                  -H "Content-Type: application/json" \
                  -d '${JsonOutput.toJson(payload)}'
            """,
            returnStdout: true
        ).trim()

        echo "Redmine update response: ${response}"
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
    createRedmineIssue("START")
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
    markStage(stageName, 'START', startRatio)
    try {
        body()
        markStage(stageName, 'SUCCESS', successRatio)
    } catch (err) {
        markStage(stageName, "FAIL - ${err}", startRatio)
        throw err
    }
}

def checkout() {
    stage('Git Checkout') {
        runStageWithRedmine('Git Checkout', 10, 20) {
            echo("===============[Git Checkout] 단계 시작===============")

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
            echo("===============[Git Checkout] 단계 종료===============")
        }
    }
}

def build() {
    stage('Build') {
        runStageWithRedmine('Build', 25, 40) {
            echo("===============[Build] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleBuild()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenBuild()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo("===============[Build] 단계 종료===============")
        }
    }
}

def test() {
    stage('Test') {
        runStageWithRedmine('Test', 45, 55) {
            echo("===============[Test] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleTest()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenTest()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo("===============[Test] 단계 종료===============")
        }
    }
}

def gradleTest() {
    stage('Gradle Test') {
        echo("===============[Gradle Test] 단계 시작===============")

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

        echo("===============[Gradle Test] 단계 종료===============")
    }
}

def mavenTest() {
    stage('Maven Test') {
        echo("===============[Maven Test] 단계 시작===============")

        try {
            sh('mvn test')
            echo('JUnit Test 성공적으로 실행 완료')
        } catch (err) {
            echo("JUnit Test 실패: ${err}")
            throw err
        } finally {
            sh('ls -al target/surefire-reports || echo "리포트 디렉토리 없음"')

            def reportsExist = fileExists('target/surefire-reports')
            if (reportsExist) {
                junit('**/target/surefire-reports/*.xml')
            } else {
                echo 'JUnit 리포트 파일이 존재하지 않아 junit 스텝을 건너뜁니다.'
            }
        }

        echo("===============[Maven Test] 단계 종료===============")
    }
}

def gradleBuild() {
    stage('Gradle Build') {
        echo("===============[Gradle Build] 단계 시작===============")

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

        echo("===============[Gradle Build] 단계 종료===============")
    }
}

def mavenBuild() {
    stage('Maven Build') {
        echo("===============[Maven Build] 단계 시작===============")

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

        echo("===============[Maven Build] 단계 종료===============")
    }
}

def sonarAnalysis() {
    stage('Sonar Analysis') {
        markStage('Sonar Analysis', 'START', 60)
        try {
            echo("===============[Sonar Analysis] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleSonarAnalysis()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenSonarAnalysis()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            markStage('Sonar Analysis', 'SUCCESS', 65)
            echo("===============[Sonar Analysis] 단계 종료===============")
        } catch (err) {
            markStage('Sonar Analysis', "FAIL - ${err}", 60)
            echo("[Sonar Analysis] 실패: ${err}")
            // Sonar는 실패해도 계속 진행
        }
    }
}

def gradleSonarAnalysis() {
    stage('Gradle Sonar Analysis') {
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
            echo("[Gradle Sonar Analysis] 실패: ${err}")
            throw err
        }
    }
}

def mavenSonarAnalysis() {
    stage('Maven Sonar Analysis') {
        try {
            echo("===============[Maven Sonar Analysis] 단계 시작===============")

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

            echo("===============[Maven Sonar Analysis] 단계 종료===============")
        } catch (err) {
            echo("[Maven Sonar Analysis] 실패: ${err}")
            throw err
        }
    }
}

def sonarAnalysisResult() {
    stage('Sonar Analysis Result') {
        markStage('Sonar Analysis Result', 'START', 66)
        try {
            echo("===============[Sonar Analysis Result] 단계 시작===============")

            def reportTaskExists = fileExists('build/sonar/report-task.txt')
            if (!reportTaskExists) {
                def mavenReportTaskExists = fileExists('target/sonar/report-task.txt')

                if (!mavenReportTaskExists) {
                    echo("SonarQube 분석 결과 파일(report-task.txt)이 존재하지 않습니다. 품질 게이트 검사를 건너뜁니다.")
                    updateRedmineIssue("[Sonar Analysis Result] report-task.txt 없음. 품질 게이트 생략", 68)
                    echo("===============[Sonar Analysis Result] 단계 종료===============")
                    return
                }
            }

            withSonarQubeEnv('sonarqube') {
                timeout(time: 10, unit: 'MINUTES') {
                    try {
                        def qg = waitForQualityGate()
                        echo("소나큐브 품질 게이트 검사 결과: ${qg.status}")

                        if (qg.status != 'OK') {
                            updateRedmineIssue("[Sonar Analysis Result] 품질 게이트 실패: ${qg.status}", 68)
                            echo("경고: 품질 게이트 검사에 실패했지만 파이프라인은 계속 진행됩니다.")
                        } else {
                            updateRedmineIssue("[Sonar Analysis Result] 품질 게이트 통과", 70)
                            echo("소나큐브 품질 게이트 검사 통과")
                        }
                    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                        updateRedmineIssue("[Sonar Analysis Result] 품질 게이트 대기 타임아웃", 68)
                        echo("소나큐브 분석 결과 대기 중 타임아웃이 발생했습니다.")
                    } catch (Exception e) {
                        updateRedmineIssue("[Sonar Analysis Result] 품질 게이트 검사 오류: ${e.message}", 68)
                        echo("소나큐브 품질 게이트 검사 중 오류 발생: ${e.message}")
                    }
                }
            }

            markStage('Sonar Analysis Result', 'SUCCESS', 70)
            echo("===============[Sonar Analysis Result] 단계 종료===============")
        } catch (err) {
            markStage('Sonar Analysis Result', "FAIL - ${err}", 66)
            echo("[Sonar Analysis Result] 실패: ${err}")
            echo("경고: Sonar Analysis Result 단계에서 오류가 발생했지만 파이프라인은 계속 진행됩니다.")
        }
    }
}

def dockerBuild() {
    stage('Docker Build') {
        runStageWithRedmine('Docker Build', 72, 82) {
            echo("===============[Docker Build] 단계 시작===============")

            container('docker-dind') {
                sh '''
                    docker build -t "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" .
                    docker tag "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:latest"
                '''
            }

            echo("===============[Docker Build] 단계 종료===============")
        }
    }
}

def dockerPush() {
    stage('Docker Push') {
        runStageWithRedmine('Docker Push', 83, 90) {
            echo("===============[Docker Push] 단계 시작===============")

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

            echo("===============[Docker Push] 단계 종료===============")
        }
    }
}

def harborImageScan() {
    stage('Harbor Image Scan') {
        markStage('Harbor Image Scan', 'START', 91)
        try {
            echo("===============[Harbor Image Scan] 단계 시작===============")

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

                    echo("아직 취약점 데이터가 없습니다. ${interval}초 후 재시도 (경과: ${waited}s)")
                    sleep interval
                    waited += interval
                }

                if (!found) {
                    error("Harbor 취약점 결과를 ${maxWait}초 내에 받지 못했습니다.")
                }

                def parsed = readJSON text: vulnResult
                def reportKey = parsed.keySet().find { it.startsWith('application/vnd.scanner.adapter.vuln.report.harbor') }
                def report = parsed[reportKey]
                def severity = report?.severity ?: 'Unknown'
                def vulnerabilities = report?.vulnerabilities ?: []

                echo("취약점 등급 : ${severity}")
                def cveList = vulnerabilities.collect { it.id }.join(', ')
                echo("취약점 코드 : ${cveList ?: '없음'}")

                updateRedmineIssue(
                    """[Harbor Image Scan] 완료
- Severity: ${severity}
- CVE: ${cveList ?: '없음'}
""",
                    94
                )

                if (severity.toLowerCase() == 'critical') {
                    echo("Critical 취약점 발견")
                    // 필요하면 여기서 실패 처리
                    // error("Critical 취약점 발견: 파이프라인 실패 처리")
                } else {
                    echo("Critical 취약점 없음, latest 태그 push 실행")
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

                echo("취약점 상세 결과: ${vulnResult}")
            }

            markStage('Harbor Image Scan', 'SUCCESS', 95)
            echo("===============[Harbor Image Scan] 단계 종료===============")
        } catch (err) {
            markStage('Harbor Image Scan', "FAIL - ${err}", 91)
            error("Harbor Image Scan 단계에서 오류 발생")
        }
    }
}

def gitOpsDeploy() {
    stage('GitOps Deploy') {
        runStageWithRedmine('GitOps Deploy', 95, 100) {
            echo("===============[GitOps Deploy] 단계 시작===============")

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

                        echo "생성된 PR 번호: ${prNumber}"

                        updateRedmineIssue(
                            """[GitOps Deploy] PR 생성 완료
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
                                """[GitOps Deploy] Merge 성공 및 feature branch 삭제 완료
- PR 번호: ${prNumber}
- 배포 태그: ${env.APP_VERSION}
""",
                                100
                            )

                            echo("Gitea Merge Success, Feature Branch Deleted")
                        } else {
                            updateRedmineIssue(
                                """[GitOps Deploy] Merge 실패
- PR 번호: ${prNumber}
- Feature Branch: ${featureBranch}
""",
                                97
                            )
                            error("Gitea Merge Failed")
                        }
                    } else {
                        updateRedmineIssue(
                            """[GitOps Deploy] 현재 GitOps 태그와 동일하여 업데이트 생략
- Current Tag: ${currentVersion}
""",
                            100
                        )
                        echo("현재 GitOps 태그와 동일하므로 업데이트하지 않습니다.")
                    }
                }
            }

            echo("===============[GitOps Deploy] 단계 종료===============")
        }
    }
}

def transferEmptyFileToFtc() {
    stage('Transfer File To FTC') {
        runStageWithRedmine('Transfer File To FTC', 96, 100) {
            echo("===============[Transfer File To FTC] 단계 시작===============")

            sh '''
                set -e
                FILE_NAME="ftc-test-${BUILD_NUMBER}.txt"

                echo "=== debug start ==="
                id
                whoami
                ls -ld /ftc-upload
                echo "=== debug end ==="

                cat <<EOF > "${FILE_NAME}"
Hello FTC
Build Number: ${BUILD_NUMBER}
Date: $(date)
EOF

                cp "${FILE_NAME}" /ftc-upload/
                ls -l /ftc-upload/"${FILE_NAME}"
            '''

            echo("===============[Transfer File To FTC] 단계 종료===============")
        }
    }
}

def transferSourceArchiveToFtc(String sourceDir = 'source', String orgName = '', String appName = 'app') {
    stage('Transfer Source Archive To FTC') {
        runStageWithRedmine('Transfer Source Archive To FTC', 96, 100) {
            echo("===============[Transfer Source Archive To FTC] 단계 시작===============")

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

            echo("===============[Transfer Source Archive To FTC] 단계 종료===============")
        }
    }
}

return this