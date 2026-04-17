def runGiteaPrPipeline(Map config = [:]) {
    def triggerToken = config.triggerToken ?: ''
    def redmineProjectId = config.redmineProjectId ?: ''
    def targetBranch = config.targetBranch ?: 'main'
    def redmineUrl = config.redmineUrl ?: 'http://redmine.cicd.svc.cluster.local'

    if (!triggerToken?.trim()) {
        error('config.triggerToken is required.')
    }
    if (!redmineProjectId?.trim()) {
        error('config.redmineProjectId is required.')
    }

    // NOTE:
    // 이 파일은 Jenkinsfile에서 `load`로 불러 실행됩니다.
    // `pipeline { agent any ... }` 같은 Declarative 문법은 "최상위 Jenkinsfile"에서만 정상 파싱되므로
    // 여기서는 순수 Step 호출만 수행합니다.

    if (env.PR_ACTION && env.PR_TO) {
        def filterText = "${env.PR_ACTION}__${env.PR_TO}"
        def filterExpression = ~/^opened__${java.util.regex.Pattern.quote(targetBranch)}$/
        if (!(filterText ==~ filterExpression)) {
            echo "이벤트 필터 불일치: ${filterText} (expected opened__${targetBranch}) -> skip"
            return
        }
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
                    "subject": "[Merge 승인 요청] ${env.REPO_NAME} PR #${env.PR_NUMBER} - ${env.PR_TITLE}",
                    "description": "저장소: ${env.REPO_FULL}\\\\nBranch: ${env.PR_FROM} → ${env.PR_TO}\\\\nPR 번호: ${env.PR_NUMBER}\\\\nPR URL: ${env.GITEA_PR_URL}\\\\n\\\\n변경 사항은 위 PR 링크에서 확인할 수 있습니다.\\\\n\\\\n승인 시 상태를 승인으로 변경해주세요.",
                    "status_id": 1,
                    "priority_id": 2,
                    "tracker_id": 4,
                    "custom_fields": [
                        {"id": 1, "value": "${env.PR_NUMBER}"},
                        {"id": 2, "value": "${env.REPO_NAME}"}
                    ]
                }
            }
            """
        )
        echo "응답: ${response.content}"
        echo "Redmine 이슈 생성 완료 - 프로젝트: ${env.REPO_NAME}, PR: #${env.PR_NUMBER}"
    }
}

return this
