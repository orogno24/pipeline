def createRedmineApprovalIssue(Map config = [:]) {
    def redmineProjectId = config.redmineProjectId ?: ''
    def redmineUrl = config.redmineUrl ?: 'http://redmine.cicd.svc.cluster.local'

    if (!redmineProjectId?.trim()) {
        error('config.redmineProjectId is required.')
    }

    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def response = httpRequest(
            httpMode: 'POST',
            url: "${redmineUrl}/issues.json",
            customHeaders: [[
                name: 'X-Redmine-API-Key',
                value: REDMINE_API_KEY
            ]],
            contentType: 'APPLICATION_JSON',
            requestBody: """
            {
                "issue": {
                    "project_id": "${redmineProjectId}",
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

return this
