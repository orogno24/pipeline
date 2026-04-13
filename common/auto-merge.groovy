pipeline {
    agent {
        kubernetes {
            label 'dind-ftc'
        }
    }

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'ISSUE_ID',          value: '$.issue.id'],
                [key: 'ISSUE_STATUS_ID',   value: '$.issue.status.id'],
                [key: 'ISSUE_STATUS_NAME', value: '$.issue.status.name'],
                [key: 'ISSUE_SUBJECT',     value: '$.issue.subject'],
                [key: 'ISSUE_DESC',        value: '$.issue.description'],
                [key: 'PROJECT_ID',        value: '$.issue.project.id'],
                [key: 'PROJECT_NAME',      value: '$.issue.project.name'],
                [key: 'TRACKER_ID',        value: '$.issue.tracker.id'],
                [key: 'TRACKER_NAME',      value: '$.issue.tracker.name']
            ],
            causeString: 'Redmine 이슈 #$ISSUE_ID 웹훅 수신',
            token: 'redmine-approve-token',
            printContributedVariables: true,
            printPostContent: true
        )
    }

    environment {
        GITEA_API_BASE           = 'http://gitea-http.cicd.svc.cluster.local:3000/api/v1'
        GITEA_GIT_BASE           = 'http://gitea-http.cicd.svc.cluster.local:3000'
        REDMINE_API_BASE         = 'http://redmine.cicd.svc.cluster.local'
        GITEA_OWNER              = 'it-department'

        REQUEST_DEPLOY_STATUS_ID = '7'   // 전송의뢰
        MERGE_REQUEST_TRACKER    = '4'
        MERGING_STATUS_ID        = '9'   // 전송중
        MERGED_STATUS_ID         = '10'  // 전송완료
        MERGE_FAIL_STATUS_ID     = '12'  // 전송실패
    }

    stages {
        stage('웹훅 수신 내용 출력') {
            steps {
                script {
                    echo '========== WEBHOOK DEBUG =========='
                    echo "ISSUE_ID          = ${env.ISSUE_ID}"
                    echo "ISSUE_STATUS_ID   = ${env.ISSUE_STATUS_ID}"
                    echo "ISSUE_STATUS_NAME = ${env.ISSUE_STATUS_NAME}"
                    echo "TRACKER_ID        = ${env.TRACKER_ID}"
                    echo "PROJECT_NAME      = ${env.PROJECT_NAME}"
                    echo "ISSUE_DESC        = ${env.ISSUE_DESC}"
                    echo '=================================='
                }
            }
        }

        stage('웹훅 데이터 검증') {
            steps {
                script {
                    if (!env.ISSUE_ID?.trim()) {
                        error("ISSUE_ID 없음")
                    }

                    if (env.ISSUE_STATUS_ID != env.REQUEST_DEPLOY_STATUS_ID) {
                        echo "전송의뢰 상태 아님 → 종료"
                        currentBuild.result = 'NOT_BUILT'
                        return
                    }

                    if (env.TRACKER_ID != env.MERGE_REQUEST_TRACKER) {
                        echo "트래커 대상 아님 → 종료"
                        currentBuild.result = 'NOT_BUILT'
                        return
                    }

                    def desc = env.ISSUE_DESC ?: ''

                    env.PR_NUMBER = extractPrNumber(desc)
                    env.REPO_NAME = extractRepoName(desc)

                    if (!env.PR_NUMBER) {
                        error("PR 번호 파싱 실패")
                    }

                    if (!env.REPO_NAME) {
                        error("레포명 파싱 실패")
                    }

                    env.PROCEED_PIPELINE = 'true'
                    echo "파싱 결과 → REPO=${env.REPO_NAME}, PR=${env.PR_NUMBER}"
                }
            }
        }

        stage('Redmine 상태 업데이트 - 전송중') {
            when {
                expression { env.PROCEED_PIPELINE == 'true' }
            }
            steps {
                script {
                    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'KEY')]) {
                        httpRequest(
                            httpMode: 'PUT',
                            url: "${env.REDMINE_API_BASE}/issues/${env.ISSUE_ID}.json",
                            customHeaders: [[name: 'X-Redmine-API-Key', value: KEY]],
                            contentType: 'APPLICATION_JSON',
                            requestBody: """
                            {
                              "issue": {
                                "status_id": ${env.MERGING_STATUS_ID},
                                "notes": "Jenkins merge 시작"
                              }
                            }
                            """
                        )
                    }
                }
            }
        }

        stage('Gitea PR Merge') {
            when {
                expression { env.PROCEED_PIPELINE == 'true' }
            }
            steps {
                script {
                    def response = httpRequest(
                        httpMode: 'POST',
                        url: "${env.GITEA_API_BASE}/repos/${env.GITEA_OWNER}/${env.REPO_NAME}/pulls/${env.PR_NUMBER}/merge",
                        authentication: 'gitea-credentials',
                        contentType: 'APPLICATION_JSON',
                        requestBody: """
                        {
                          "Do": "merge",
                          "merge_message_field": "Auto merged by Jenkins (#${env.ISSUE_ID})"
                        }
                        """,
                        validResponseCodes: '200:409'
                    )

                    echo "Merge 응답: ${response.status}"

                    if (response.status == 200) {
                        env.MERGE_RESULT = 'SUCCESS'
                    } else {
                        env.MERGE_RESULT = 'FAIL'
                    }
                }
            }
        }

        stage('Redmine 상태 업데이트 - 결과 반영') {
            when {
                expression { env.PROCEED_PIPELINE == 'true' }
            }
            steps {
                script {
                    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'KEY')]) {
                        def status = env.MERGE_RESULT == 'SUCCESS' ? env.MERGED_STATUS_ID : env.MERGE_FAIL_STATUS_ID

                        httpRequest(
                            httpMode: 'PUT',
                            url: "${env.REDMINE_API_BASE}/issues/${env.ISSUE_ID}.json",
                            customHeaders: [[name: 'X-Redmine-API-Key', value: KEY]],
                            contentType: 'APPLICATION_JSON',
                            requestBody: """
                            {
                              "issue": {
                                "status_id": ${status},
                                "notes": "Jenkins merge 결과: ${env.MERGE_RESULT}"
                              }
                            }
                            """
                        )
                    }
                }
            }
        }
    }
}

@NonCPS
def extractRepoName(String desc) {
    def line = desc.readLines().find { it.startsWith('저장소:') }
    if (!line) return ''

    def value = line.replaceFirst(/^저장소:\s*/, '').trim()
    def parts = value.tokenize('/')

    return parts ? parts[-1].trim() : ''
}

@NonCPS
def extractPrNumber(String desc) {
    def line = desc.readLines().find { it.startsWith('PR 번호:') }
    if (!line) return ''

    def value = line.replaceFirst(/^PR 번호:\s*/, '').trim()
    return value.isInteger() ? value : ''
}