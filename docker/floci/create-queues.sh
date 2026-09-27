#!/bin/sh
# 큐 5개와 각각의 DLQ. 5번 받고도 처리하지 못한 메시지는 DLQ 로 옮긴다.
set -e
ENDPOINT=http://floci:4566
MAX_RECEIVE_COUNT=5

until aws --endpoint-url "$ENDPOINT" sqs list-queues >/dev/null 2>&1; do
  sleep 1
done

for queue in preorder-register preorder-cancel preorder-events order-events notification; do
  aws --endpoint-url "$ENDPOINT" sqs create-queue --queue-name "$queue-dlq" >/dev/null
  dlq_url=$(aws --endpoint-url "$ENDPOINT" sqs get-queue-url --queue-name "$queue-dlq" --query QueueUrl --output text)
  dlq_arn=$(aws --endpoint-url "$ENDPOINT" sqs get-queue-attributes --queue-url "$dlq_url" \
    --attribute-names QueueArn --query Attributes.QueueArn --output text)
  aws --endpoint-url "$ENDPOINT" sqs create-queue --queue-name "$queue" --attributes \
    "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"$MAX_RECEIVE_COUNT\\\"}\"}" \
    >/dev/null
  echo "created $queue (dlq: $queue-dlq)"
done
