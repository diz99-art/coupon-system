"""Grafana 대시보드(dashboards/coupon.json)를 만든다. 패널과 PromQL을 여기서 고치고 다시 실행한다.
사용: python3 monitoring/grafana/gen_dashboard.py monitoring/grafana/dashboards/coupon.json
Grafana는 10초마다 파일을 다시 읽으므로 실행 중인 화면에 곧 반영된다.
"""
import json, sys
DS = {"type": "prometheus", "uid": "prometheus"}
APP = '{uri!~"/actuator.*"}'
panels = []
pid = [0]

def nid():
    pid[0] += 1
    return pid[0]

def row(title, y):
    panels.append({"type": "row", "id": nid(), "title": title, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})

def ts(title, desc, targets, x, y, w=8, h=8, unit="short", stack=False, minv=None, maxv=None, overrides=None):
    p = {"type": "timeseries", "id": nid(), "title": title, "description": desc, "datasource": DS,
         "gridPos": {"h": h, "w": w, "x": x, "y": y},
         "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 10,
                                                               "showPoints": "never",
                                                               "stacking": {"mode": "normal" if stack else "none"}}},
                         "overrides": overrides or []},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                     "tooltip": {"mode": "multi", "sort": "desc"}},
         "targets": [{"datasource": DS, "refId": chr(65 + i), "expr": e, "legendFormat": l}
                     for i, (e, l) in enumerate(targets)]}
    if minv is not None: p["fieldConfig"]["defaults"]["min"] = minv
    if maxv is not None: p["fieldConfig"]["defaults"]["max"] = maxv
    panels.append(p)

def stat(title, desc, targets, x, y, w=4, h=4, unit="short", color="green"):
    panels.append({"type": "stat", "id": nid(), "title": title, "description": desc, "datasource": DS,
                   "gridPos": {"h": h, "w": w, "x": x, "y": y},
                   "fieldConfig": {"defaults": {"unit": unit, "decimals": 0, "color": {"mode": "fixed", "fixedColor": color}}},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value", "graphMode": "none",
                               "textMode": "value_and_name"},
                   "targets": [{"datasource": DS, "refId": chr(65 + i), "expr": e, "legendFormat": l, "instant": True}
                               for i, (e, l) in enumerate(targets)]})

def color(name, fixed):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": fixed}}]}

def exact(counter, by=None):
    """기간 동안 늘어난 정확한 개수 (끝 값 - 처음 값). increase()는 구간 끝을 추정해 늘려서 500이 514처럼 나온다.
    서버가 재시작해 카운터가 0이 되면 맞지 않는다."""
    group = f" by ({by})" if by else ""
    return (f"sum{group} (max_over_time({counter}[$__range]) - min_over_time({counter}[$__range]))")

total_style = [{"matcher": {"id": "byName", "options": "합계"},
                "properties": [{"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [8, 4]}},
                               {"id": "color", "value": {"mode": "fixed", "fixedColor": "text"}}]}]

y = 0
row("요청 (nginx 뒤 서버 2대)", y); y += 1
ts("TPS (초당 처리 요청)", "서버별 초당 응답 수와 합계. 관리용 주소(/actuator)는 뺀다.",
   [(f'sum by (instance) (rate(http_server_requests_seconds_count{APP}[$__rate_interval]))', "{{instance}}"),
    (f'sum(rate(http_server_requests_seconds_count{APP}[$__rate_interval]))', "합계")],
   0, y, unit="reqps", overrides=total_style)
ts("응답 시간 p95", "요청 95%가 이 시간 안에 끝났다. 서버별 값과 두 서버를 합친 값. 히스토그램 구간으로 계산해 근삿값이다.",
   [(f'histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{APP}[$__rate_interval])))', "{{instance}}"),
    (f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{APP}[$__rate_interval])))', "합계")],
   8, y, unit="s", overrides=total_style)
ts("응답 상태 코드", "초당 응답 수를 상태 코드별로. 409는 매진·중복 같은 정상 거절이다.",
   [(f'sum by (status) (rate(http_server_requests_seconds_count{APP}[$__rate_interval]))', "{{status}}")],
   16, y, unit="reqps")
y += 8

REQ = f'sum by (instance) (rate(http_server_requests_seconds_count{APP}[$__rate_interval]))'
row("요청 1건당 비용", y); y += 1
ts("요청 1건당 CPU 시간", "서버별 (CPU 사용 시간 증가량) ÷ (처리한 요청 수). 같은 일을 더 적은 CPU로 하면 내려간다. 초당 요청이 50건 이하인 구간은 값이 들쭉날쭉해서 그리지 않는다",
   [(f'sum by (instance) (rate(process_cpu_time_ns_total[$__rate_interval])) / 1e6 / ({REQ} > 50)', "{{instance}}")],
   0, y, w=12, h=7, unit="ms", minv=0)
ts("요청 1건당 DB 커넥션 사용", "서버별 (커넥션을 빌린 횟수) ÷ (처리한 요청 수). 발급 API가 DB를 쓰지 않으면 0에 가깝다 (Kafka 컨슈머 저장도 함께 센다)",
   [(f'sum by (instance) (rate(hikaricp_connections_usage_seconds_count[$__rate_interval])) / ({REQ} > 50)', "{{instance}}")],
   12, y, w=12, h=7, unit="short", minv=0)
y += 7

row("발급 (커스텀 지표)", y); y += 1
stat("발급 성공", "선택한 기간 동안 발급 확정 수 (두 서버 합)",
     [(exact("coupon_issue_success_total"), "발급 성공")], 0, y, color="green")
stat("발급 거절", "선택한 기간 동안 거절 수 (두 서버 합)",
     [(exact("coupon_issue_rejected_total"), "발급 거절")], 0, y + 4, color="orange")
ts("발급 결과 (초당)", "API의 발급 성공·거절 사유별 초당 건수와, Kafka 컨슈머가 DB에 저장한 초당 건수. 성공보다 저장이 느리면 토픽에 메시지가 쌓인다",
   [('sum(rate(coupon_issue_success_total[$__rate_interval]))', "성공"),
    ('sum(rate(coupon_issue_save_delay_seconds_count[$__rate_interval]))', "DB 저장 (컨슈머)"),
    ('sum by (reason) (rate(coupon_issue_rejected_total[$__rate_interval]))', "거절: {{reason}}")],
   4, y, w=10, unit="ops",
   overrides=[color("성공", "green"), color("DB 저장 (컨슈머)", "yellow"), color("거절: COUPON_SOLD_OUT", "purple"),
              color("거절: COUPON_ALREADY_ISSUED", "blue"), color("거절: COUPON_ISSUE_BUSY", "red")])
ts("Kafka 컨슈머 처리 지연", "발급 확정(API 응답)부터 컨슈머가 DB에 저장할 때까지 걸린 시간(p95·최대)과, 토픽에 쌓여 아직 못 읽은 메시지 수(lag)",
   [('histogram_quantile(0.95, sum by (le) (rate(coupon_issue_save_delay_seconds_bucket[$__rate_interval])))', "저장 지연 p95"),
    ('max(coupon_issue_save_delay_seconds_max)', "저장 지연 최대"),
    ('sum(kafka_consumer_fetch_manager_records_lag{topic!~".*DLT"})', "쌓인 메시지(lag)")],
   14, y, w=10, unit="s",
   overrides=[{"matcher": {"id": "byName", "options": "쌓인 메시지(lag)"},
               "properties": [{"id": "unit", "value": "short"}, {"id": "custom.axisPlacement", "value": "right"},
                              {"id": "custom.drawStyle", "value": "bars"}]}])
y += 8
stat("대사 보정 건수", "선택한 기간 동안 대사 작업이 채운 발급 (db: Redis에만 있던 것을 DB에 저장, redis: DB에만 있던 것을 Redis에 복구)",
     [(exact("coupon_reconcile_corrected_total", "target"), "{{target}}")], 0, y, w=8, h=6, color="purple")
stat("대사 작업 실행 횟수 (서버별)", "선택한 기간 동안 각 서버가 대사 작업을 실행한 횟수. ShedLock이 있으면 합이 분 수와 같다",
     [(exact("coupon_reconcile_duration_seconds_count", "instance"), "{{instance}}")], 8, y, w=8, h=6, color="blue")
ts("대사 작업 누적 실행 (서버별)", "서버별 누적 실행 횟수. 계단이 오른 시각에 그 서버가 실행했다. 같은 분에 두 서버가 함께 오르면 중복 실행이다",
   [('max by (instance) (coupon_reconcile_duration_seconds_count)', "{{instance}}")],
   16, y, w=8, h=6, unit="short", minv=0)
panels[-1]["fieldConfig"]["defaults"]["decimals"] = 0
panels[-1]["fieldConfig"]["defaults"]["custom"]["lineInterpolation"] = "stepAfter"
y += 6

row("자원", y); y += 1
ts("CPU 사용률", "프로세스 CPU 사용률. 컨테이너마다 CPU 1개로 제한해서 100%가 한계다",
   [('max by (instance) (process_cpu_usage)', "{{instance}}")], 0, y, unit="percentunit", minv=0, maxv=1)
ts("JVM 힙 메모리", "힙 사용량과 최대값 (컨테이너 메모리 1GB의 75%)",
   [('sum by (instance) (jvm_memory_used_bytes{area="heap"})', "{{instance}} 사용"),
    ('max(sum by (instance) (jvm_memory_max_bytes{area="heap"}))', "최대")], 8, y, unit="bytes")
ts("GC 시간 비율", "1초 중 GC로 멈춘 시간의 비율",
   [('sum by (instance) (rate(jvm_gc_pause_seconds_sum[$__rate_interval]))', "{{instance}}")],
   16, y, unit="percentunit", minv=0)
y += 8
ts("DB 커넥션 풀 (HikariCP)", "사용 중(active)과 커넥션을 기다리는 스레드(pending). 풀 크기는 서버마다 10개",
   [('sum by (instance) (hikaricp_connections_active)', "{{instance}} 사용 중"),
    ('sum by (instance) (hikaricp_connections_pending)', "{{instance}} 대기"),
    ('max(hikaricp_connections_max)', "풀 크기")], 0, y)
ts("Tomcat 스레드", "요청을 처리 중인 스레드 수. 최대 200개",
   [('sum by (instance) (tomcat_threads_busy_threads)', "{{instance}} 처리 중"),
    ('max(tomcat_threads_config_max_threads)', "최대")], 8, y)
ts("열린 연결 수", "Tomcat이 들고 있는 연결 수. 스레드가 모자라면 연결은 열린 채 차례를 기다린다",
   [('sum by (instance) (tomcat_connections_current_connections)', "{{instance}}")], 16, y)

dash = {"uid": "coupon", "title": "쿠폰 발급 시스템", "tags": ["coupon"], "timezone": "browser",
        "editable": True, "refresh": "5s", "schemaVersion": 39, "version": 1,
        "time": {"from": "now-15m", "to": "now"}, "panels": panels}
json.dump(dash, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
print(len(panels), "panels")
