pipeline {
    agent {
        kubernetes {
            label 'dind-ftc'
        }
    }

    //triggers {
    //    cron('H/5 * * * *')
    //}

    stages {
        stage('승인 이슈 조회 및 Merge') {
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')
                    ]) {
                        def response = httpRequest(
                            url: "http://redmine.cicd.svc.cluster.local/issues.json?status_id=7&tracker_id=4&limit=25",
                            customHeaders: [[name: 'X-Redmine-API-Key', value: "${REDMINE_API_KEY}"]]
                        )

                        def issues = readJSON text: response.content

                        if (issues.issues.size() == 0) {
                            echo "승인된 이슈 없음"
                            currentBuild.result = 'NOT_BUILT'
                            return
                        }

                        env.HAS_APPROVED = 'true'

                        issues.issues.each { issue ->
                            def desc = issue.description ?: ''
                            def prNumber = extractPrNumber(desc)
                            def repoName = extractRepoName(desc)

                            if (!prNumber || !repoName) {
                                echo "PR 번호 또는 레포명 파싱 실패 - 이슈 #${issue.id} 스킵"
                                return
                            }

                            echo "처리 중 - 레포: ${repoName}, PR: #${prNumber}"

                            def mergeResponse = httpRequest(
                                httpMode: 'POST',
                                url: "http://gitea-http.cicd.svc.cluster.local:3000/api/v1/repos/admin/${repoName}/pulls/${prNumber}/merge",
                                authentication: 'gitea-credentials',
                                contentType: 'APPLICATION_JSON',
                                requestBody: '{"Do": "merge", "merge_message_field": "Auto merged by Jenkins"}',
                                validResponseCodes: '100:499'
                            )

                            if (mergeResponse.status == 200) {
                                echo "Merge 완료 - ${repoName} PR #${prNumber}"
                            } else {
                                echo "Merge 실패 또는 이미 처리됨 - ${repoName} PR #${prNumber} (${mergeResponse.status})"
                            }

                            httpRequest(
                                httpMode: 'PUT',
                                url: "http://redmine.cicd.svc.cluster.local/issues/${issue.id}.json",
                                customHeaders: [[name: 'X-Redmine-API-Key', value: "${REDMINE_API_KEY}"]],
                                contentType: 'APPLICATION_JSON',
                                requestBody: '{"issue": {"status_id": 10}}'
                            )
                            echo "Redmine 이슈 #${issue.id} 완료 처리"
                        }
                    }
                }
            }
        }

        stage('소스 체크아웃') {
            when {
                expression { env.HAS_APPROVED == 'true' }
            }
            steps {
                git(
                    url: 'http://gitea-http.cicd.svc.cluster.local:3000/admin/msaproject.git',
                    branch: 'main',
                    credentialsId: 'gitea-credentials'
                )
            }
        }

        stage('Transfer Source Archive To FTC') {
            when {
                expression { env.HAS_APPROVED == 'true' }
            }
            steps {
                sh '''
                    set -e
                    APP_NAME="msaproject"
                    COMMIT_SHA="$(git rev-parse --short HEAD)"
                    BUILD_DATE="$(date +%Y%m%d%H%M%S)"
                    ARCHIVE_NAME="${APP_NAME}-main-${BUILD_NUMBER}-${BUILD_DATE}-${COMMIT_SHA}.tar.gz"
                    mkdir -p dist
                    tar --exclude='./.git' \
                        --exclude='./dist' \
                        --exclude='./.scannerwork' \
                        --exclude='./node_modules' \
                        --exclude='./target' \
                        --exclude='./build' \
                        -czf "dist/${ARCHIVE_NAME}" .
                    cp "dist/${ARCHIVE_NAME}" /ftc-upload/
                    echo "업로드 완료: ${ARCHIVE_NAME}"
                '''
            }
        }
    }

    post {
        success { echo "FTC 파이프라인 완료" }
        failure { echo "FTC 파이프라인 실패" }
    }
}

@NonCPS
def extractPrNumber(String desc) {
    def matcher = desc =~ /PR 번호: (\d+)/
    return matcher.find() ? matcher.group(1) : ''
}

@NonCPS
def extractRepoName(String desc) {
    def matcher = desc =~ /저장소: [^\/]+\/(\S+)/
    return matcher.find() ? matcher.group(1) : ''
}