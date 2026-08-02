# KidsNote Photo Backup

플래너에서 분리한 키즈노트 사진 백업·조회·다운로드 전용 앱입니다.

## 실행

1. `npm install`
2. `.env.example`을 참고해 `.env` 생성
3. `npm start`
4. `http://localhost:3100/photo` 접속

기존 사진 저장소를 복사하지 않고 연결하려면 `PHOTO_BACKUP_DIR`에 기존
`photo-backups` 절대 경로를 지정합니다. 데이터 파일은 자동으로 이동하거나
삭제하지 않습니다.
