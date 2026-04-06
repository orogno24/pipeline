import groovy.json.JsonOutput

def checkout() {
    stage('Git Checkout') {
        notifyRedmine("CHECKOUT_START")
        try {
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
            notifyRedmine("CHECKOUT_SUCCESS")
        } catch (err) {
            echo("[Git Checkout] 실패: ${err}")
            notifyRedmine("CHECKOUT_FAIL", err.toString())
            error("Git 체크아웃 단계에서 오류 발생")
        }
    }
}

def notifyRedmine(String status = "START", String extraMessage = "") {
    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def commitSha = "N/A"
        try {
            commitSha = sh(
                script: 'git rev-parse HEAD',
                returnStdout: true
            ).trim()
        } catch (ignored) {
            echo("git rev-parse HEAD 실행 전이거나 워크스페이스 상태상 커밋 SHA 조회 불가")
        }

        def version = env.APP_VERSION ?: 'latest'

        def subject = "[Jenkins][${env.JOB_NAME}] ${status} (${version})"
        def description = """\
배포 상태: ${status}

JOB 이름: ${env.JOB_NAME}
브랜치: ${env.GIT_BRANCH}
태그: ${version}
커밋: ${commitSha}
빌드 번호: ${env.BUILD_NUMBER}
빌드 URL: ${env.BUILD_URL ?: 'N/A'}
${extraMessage ? "추가 메시지: ${extraMessage}" : ""}
"""

        def payload = JsonOutput.toJson([
            issue: [
                project_id : "msaproject",
                subject    : subject,
                description: description
            ]
        ])

        writeFile file: 'redmine_issue.json', text: payload

        sh """
            curl -sS -X POST "${env.REDMINE_URL}/issues.json" \
              -H "X-Redmine-API-Key: ${REDMINE_API_KEY}" \
              -H "Content-Type: application/json" \
              --data @redmine_issue.json
        """
    }
}

def build() {
    stage('Build') {
        notifyRedmine("BUILD_START")
        try {
            echo("===============[Build] 단계 시작===============")

            if (env.BUILD_TOOL == 'gradle') {
                gradleBuild()
            } else if (env.BUILD_TOOL == 'maven') {
                mavenBuild()
            } else {
                error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
            }

            echo("===============[Build] 단계 종료===============")
            notifyRedmine("BUILD_SUCCESS")
        } catch (err) {
            echo("[Build] 실패: ${err}")
            notifyRedmine("BUILD_FAIL", err.toString())
            error("빌드 단계에서 오류 발생")
        }
    }
}

def test() {
    stage('Test') {
        notifyRedmine("TEST_START")
        echo("===============[Test] 단계 시작===============")

        script {
            try {
                if (env.BUILD_TOOL == 'gradle') {
                    gradleTest()
                } else if (env.BUILD_TOOL == 'maven') {
                    mavenTest()
                } else {
                    error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
                }

                notifyRedmine("TEST_SUCCESS")
            } catch (err) {
                echo("[Test] 실패: ${err}")
                notifyRedmine("TEST_FAIL", err.toString())
                error("테스트 단계에서 오류 발생")
            }
        }

        echo("===============[Test] 단계 종료===============")
    }
}

def gradleTest() {
    stage('Gradle Test') {
        echo("===============[Gradle Test] 단계 시작===============")

        script {
            try {
                sh('chmod +x ./gradlew')
                sh('./gradlew test')
                echo('JUnit Test 성공적으로 실행 완료')
            } catch (err) {
                echo('JUnit Test 실패')
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
        }

        echo("===============[Gradle Test] 단계 종료===============")
    }
}

def mavenTest() {
    stage('Maven Test') {
        echo("===============[Maven Test] 단계 시작===============")

        script {
            try {
                sh('mvn test')
                echo('JUnit Test 성공적으로 실행 완료')
            } catch (err) {
                echo('JUnit Test 실패')
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
        }

        echo("===============[Maven Test] 단계 종료===============")
    }
}

def gradleBuild() {
    stage('Gradle Build') {
        echo("===============[Gradle Build] 단계 시작===============")

        script {
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
        }

        echo("===============[Gradle Build] 단계 종료===============")
    }
}

def mavenBuild() {
    stage('Maven Build') {
        echo("===============[Maven Build] 단계 시작===============")

        script {
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
        }

        echo("===============[Maven Build] 단계 종료===============")
    }
}

def sonarAnalysis() {
    stage('Sonar Analysis') {
        notifyRedmine("SONAR_ANALYSIS_START")
        script {
            try {
                echo("===============[Sonar Analysis] 단계 시작===============")

                if (env.BUILD_TOOL == 'gradle') {
                    gradleSonarAnalysis()
                } else if (env.BUILD_TOOL == 'maven') {
                    mavenSonarAnalysis()
                } else {
                    error("지원하지 않는 빌드 도구입니다. BUILD_TOOL=${env.BUILD_TOOL}")
                }

                echo("===============[Sonar Analysis] 단계 종료===============")
                notifyRedmine("SONAR_ANALYSIS_SUCCESS")
            } catch (err) {
                echo("[Sonar Analysis] 실패: ${err}")
                notifyRedmine("SONAR_ANALYSIS_FAIL", err.toString())
                echo("경고: Sonar Analysis 단계에서 오류가 발생했지만 파이프라인은 계속 진행됩니다.")
            }
        }
    }
}

def gradleSonarAnalysis() {
    stage('Gradle Sonar Analysis') {
        script {
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
}

def mavenSonarAnalysis() {
    stage('Maven Sonar Analysis') {
        script {
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
        }
    }
}

def sonarAnalysisResult() {
    stage('Sonar Analysis Result') {
        notifyRedmine("SONAR_GATE_START")
        script {
            try {
                echo("===============[Sonar Analysis Result] 단계 시작===============")

                def reportTaskExists = fileExists('build/sonar/report-task.txt')
                if (!reportTaskExists) {
                    def mavenReportTaskExists = fileExists('target/sonar/report-task.txt')

                    if (!mavenReportTaskExists) {
                        echo("SonarQube 분석 결과 파일(report-task.txt)이 존재하지 않습니다. 품질 게이트 검사를 건너뜁니다.")
                        notifyRedmine("SONAR_GATE_FAIL", "report-task.txt 파일이 없어 품질 게이트 검사를 수행하지 못했습니다.")
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
                                echo("소나큐브 품질 게이트 검사 실패: ${qg.status}")
                                notifyRedmine("SONAR_GATE_FAIL", "품질 게이트 상태: ${qg.status}")
                                echo("경고: 품질 게이트 검사에 실패했지만 파이프라인은 계속 진행됩니다.")
                            } else {
                                echo("소나큐브 품질 게이트 검사 통과")
                                notifyRedmine("SONAR_GATE_SUCCESS", "품질 게이트 상태: ${qg.status}")
                            }
                        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                            echo("소나큐브 분석 결과 대기 중 타임아웃이 발생했습니다.")
                            notifyRedmine("SONAR_GATE_FAIL", "품질 게이트 대기 타임아웃 발생")
                            echo("경고: 타임아웃이 발생했지만 파이프라인은 계속 진행됩니다.")
                        } catch (Exception e) {
                            echo("소나큐브 품질 게이트 검사 중 오류 발생: ${e.message}")
                            notifyRedmine("SONAR_GATE_FAIL", "품질 게이트 검사 오류: ${e.message}")
                            echo("경고: 오류가 발생했지만 파이프라인은 계속 진행됩니다.")
                        }
                    }
                }

                echo("===============[Sonar Analysis Result] 단계 종료===============")
            } catch (err) {
                echo("[Sonar Analysis Result] 실패: ${err}")
                notifyRedmine("SONAR_GATE_FAIL", err.toString())
                echo("경고: Sonar Analysis Result 단계에서 오류가 발생했지만 파이프라인은 계속 진행됩니다.")
            }
        }
    }
}

def dockerBuild() {
    stage('Docker Build') {
        notifyRedmine("DOCKER_BUILD_START")
        try {
            echo("===============[Docker Build] 단계 시작===============")

            container('docker-dind') {
                sh '''
                    docker build -t "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" .
                    docker tag "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:$APP_VERSION" "$DOCKER_REGISTRY_URL/$DOCKER_IMAGE_NAME:latest"
                '''
            }

            echo("===============[Docker Build] 단계 종료===============")
            notifyRedmine("DOCKER_BUILD_SUCCESS")
        } catch (err) {
            echo("Docker Build 실패: ${err.getMessage()}")
            notifyRedmine("DOCKER_BUILD_FAIL", err.toString())
            error("Docker Build 단계에서 오류 발생")
        }
    }
}

def dockerPush() {
    stage('Docker Push') {
        notifyRedmine("DOCKER_PUSH_START")
        try {
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
            notifyRedmine("DOCKER_PUSH_SUCCESS")
        } catch (err) {
            echo("[Docker Push] 실패: ${err}")
            notifyRedmine("DOCKER_PUSH_FAIL", err.toString())
            error("Docker Push 단계에서 오류 발생")
        }
    }
}

def harborImageScan() {
    stage('Harbor Image Scan') {
        notifyRedmine("HARBOR_SCAN_START")
        echo("===============[Harbor Image Scan] 단계 시작===============")

        script {
            try {
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

                    if (severity.toLowerCase() == 'critical') {
                        echo("Critical 취약점 발견")
                        notifyRedmine("HARBOR_SCAN_FAIL", "Critical 취약점 발견. CVE: ${cveList ?: '없음'}")
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
                        notifyRedmine("HARBOR_SCAN_SUCCESS", "취약점 등급: ${severity}, CVE: ${cveList ?: '없음'}")
                    }

                    echo("취약점 상세 결과: ${vulnResult}")
                }

                echo("===============[Harbor Image Scan] 단계 종료===============")
            } catch (err) {
                echo("[Harbor Image Scan] 실패: ${err}")
                notifyRedmine("HARBOR_SCAN_FAIL", err.toString())
                error("Harbor Image Scan 단계에서 오류 발생")
            }
        }
    }
}

def gitOpsDeploy() {
    stage('GitOps Deploy') {
        notifyRedmine("GITOPS_DEPLOY_START")
        try {
            echo("===============[GitOps Deploy] 단계 시작===============")

            git(
                url: env.GITOPS_URL,
                branch: env.GITOPS_BRANCH,
                credentialsId: env.GITOPS_CREDENTIALS_ID
            )

            script {
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
                                        }' |
                                    sed -n 's/.*"number":[ ]*\\([0-9]*\\).*/\\1/p'
                                """,
                                returnStdout: true
                            ).trim()

                            echo "생성된 PR 번호: ${prNumber}"

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
                                echo("Gitea Merge Success, Feature Branch Deleted")
                            } else {
                                error("Gitea Merge Failed")
                            }
                        } else {
                            echo("현재 GitOps 태그와 동일하므로 업데이트하지 않습니다.")
                        }
                    }
                }
            }

            echo("===============[GitOps Deploy] 단계 종료===============")
            notifyRedmine("GITOPS_DEPLOY_SUCCESS")
        } catch (err) {
            echo("[GitOps Deploy] 실패: ${err}")
            notifyRedmine("GITOPS_DEPLOY_FAIL", err.toString())
            error("GitOps Deploy 단계에서 오류 발생")
        }
    }
}

def transferEmptyFileToFtc() {
    stage('Transfer File To FTC') {
        notifyRedmine("FTC_TRANSFER_START")
        try {
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
            notifyRedmine("FTC_TRANSFER_SUCCESS")
        } catch (err) {
            echo("[Transfer File To FTC] 실패: ${err}")
            notifyRedmine("FTC_TRANSFER_FAIL", err.toString())
            error("FTC 전송 단계에서 오류 발생")
        }
    }
}

def transferSourceArchiveToFtc(String sourceDir = 'source', String appName = 'app', String branchName = 'main') {
    stage('Transfer Source Archive To FTC') {
        notifyRedmine("FTC_SOURCE_ARCHIVE_START")
        try {
            echo("===============[Transfer Source Archive To FTC] 단계 시작===============")

            sh """
                set -e

                APP_NAME="${appName}"
                BRANCH_NAME="${branchName}"
                COMMIT_SHA=\$(git -C "${sourceDir}" rev-parse --short HEAD)
                BUILD_DATE=\$(date +%Y%m%d%H%M%S)
                ARCHIVE_NAME="\${APP_NAME}-\${BRANCH_NAME}-${BUILD_NUMBER}-\${BUILD_DATE}-\${COMMIT_SHA}.tar.gz"

                echo "=== debug start ==="
                id
                whoami
                pwd
                ls -al
                ls -al "${sourceDir}"
                ls -ld /ftc-upload
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
            notifyRedmine("FTC_SOURCE_ARCHIVE_SUCCESS")
        } catch (err) {
            echo("[Transfer Source Archive To FTC] 실패: ${err}")
            notifyRedmine("FTC_SOURCE_ARCHIVE_FAIL", err.toString())
            error("FTC 소스 압축 전송 단계에서 오류 발생")
        }
    }
}

return this