pipeline {
    agent any

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'PR_ACTION',    value: '$.action'],
                [key: 'PR_NUMBER',    value: '$.number'],
                [key: 'PR_TITLE',     value: '$.pull_request.title'],
                [key: 'PR_FROM',      value: '$.pull_request.head.label'],
                [key: 'PR_TO',        value: '$.pull_request.base.label'],
                [key: 'REPO_NAME',    value: '$.repository.name'],
                [key: 'REPO_FULL',    value: '$.repository.full_name'],
                [key: 'GITEA_PR_URL', value: '$.pull_request.url']
            ],
            causeString: 'PR #$PR_NUMBER 생성됨',
            token: 'merge-approve-token-dev-fe',
            regexpFilterText: '$PR_ACTION__$PR_TO',
            regexpFilterExpression: 'opened__main'
        )
    }

    stages {
        stage('Redmine 승인 이슈 생성') {
            steps {
                script {
                    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
                        def response = httpRequest(
                            httpMode: 'POST',
                            url: "http://redmine.cicd.svc.cluster.local/issues.json",
                            customHeaders: [[
                                name: 'X-Redmine-API-Key',
                                value: "${REDMINE_API_KEY}"
                            ]],
                            contentType: 'APPLICATION_JSON',
                            requestBody: """
                            {
                                "issue": {
                                    "project_id": "jt-genie-lam",
                                    "subject": "[Merge 승인 요청] ${REPO_NAME} PR #${PR_NUMBER} - ${PR_TITLE}",
                                    "description": "저장소: ${REPO_FULL}\\nBranch: ${PR_FROM} → ${PR_TO}\\nPR 번호: ${PR_NUMBER}\\nPR URL: ${GITEA_PR_URL}\\n\\n변경 사항은 위 PR 링크에서 확인할 수 있습니다.\\n\\n승인 시 상태를 승인으로 변경해주세요.",
                                    "status_id": 1,
                                    "priority_id": 2,
                                    "tracker_id": 4,
                                    "custom_fields": [
                                        {"id": 1, "value": "${PR_NUMBER}"},
                                        {"id": 2, "value": "${REPO_NAME}"}
                                    ]
                                }
                            }
                            """
                        )
                        echo "응답: ${response.content}" 
                        echo "Redmine 이슈 생성 완료 - 프로젝트: ${REPO_NAME}, PR: #${PR_NUMBER}"
                    }
                }
            }
        }
    }

    post {
        success { echo "승인 요청 완료 - ${REPO_NAME} PR #${PR_NUMBER}" }
        failure { echo "Redmine 이슈 생성 실패" }
    }
}