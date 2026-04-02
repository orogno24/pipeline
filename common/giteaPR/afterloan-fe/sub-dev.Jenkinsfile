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
            token: 'fesubdevtoken',
            regexpFilterText: '$PR_ACTION__$PR_TO',
            regexpFilterExpression: 'opened__dev'
        )
    }

    stages {
        stage('Redmine 승인 이슈 생성') {
            steps {
                script {
                    def pr = load 'common/giteaPR.groovy'
                    pr.createIssue(redmineProjectId: 'jt-genie-lam')
                }
            }
        }
    }
}