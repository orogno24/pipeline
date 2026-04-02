def createIssue(Map config = [:]) {
    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def response = httpRequest(
            httpMode: 'POST',
            url: config.redmineUrl ?: 'http://redmine.cicd.svc.cluster.local/issues.json',
            customHeaders: [[
                name: 'X-Redmine-API-Key',
                value: "${REDMINE_API_KEY}"
            ]],
            contentType: 'APPLICATION_JSON',
            requestBody: """
            {
                "issue": {
                    "project_id": "${config.redmineProjectId}",
                    "subject": "[Merge 승인 요청] ${env.REPO_NAME} PR #${env.PR_NUMBER} - ${env.PR_TITLE}",
                    "description": "저장소: ${env.REPO_FULL}\\nBranch: ${env.PR_FROM} → ${env.PR_TO}\\nPR 번호: ${env.PR_NUMBER}\\nPR URL: ${env.GITEA_PR_URL}",
                    "status_id": 1,
                    "priority_id": 2,
                    "tracker_id": 4
                }
            }
            """
        )
        echo "응답: ${response.content}"
    }
}

return this