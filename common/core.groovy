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

                echo "$RESPONSE" | sed -n 's/.*"id":[ ]*\\([0-9][0-9]*\\).*/\\1/p' | head -n 1
            ''',
            returnStdout: true
        ).trim()

        if (!issueId) {
            error("Redmine 이슈 생성 실패")
        }

        env.REDMINE_ISSUE_ID = issueId
        echo "Redmine issue created: ${env.REDMINE_ISSUE_ID}"
    }
}

def updateRedmineIssue(String note, Integer doneRatio = null) {
    if (!env.REDMINE_ISSUE_ID?.trim()) {
        echo "REDMINE_ISSUE_ID 가 없어 Redmine 업데이트를 건너뜁니다."
        return
    }

    withCredentials([string(credentialsId: 'redmine-api-key', variable: 'REDMINE_API_KEY')]) {
        def ratioPart = doneRatio != null ? ""","done_ratio":${doneRatio}""" : ""

        writeFile file: 'redmine_update.json', text: """{
  "issue": {
    "notes": ${groovy.json.JsonOutput.toJson(note)}${ratioPart}
  }
}"""

        sh '''
            curl -sS -X PUT "$REDMINE_URL/issues/$REDMINE_ISSUE_ID.json" \
              -H "X-Redmine-API-Key: $REDMINE_API_KEY" \
              -H "Content-Type: application/json" \
              --data @redmine_update.json
        '''
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