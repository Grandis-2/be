package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * GET_LOCK 은 오류가 나면 NULL 을 돌려준다(MySQL). 이를 다른 연결이 쥔 것(0)처럼 건너뛰면 정리가 돌지 않았는데 종료 코드 0 으로 끝나
 * 경보가 울리지 않는다 — 던져서 1 로 끝나게 한다. MySQL 에서 NULL 을 일으킬 수 없어 연결을 흉내 낸다. 0 · 1 은 ImageCleanupTest 가 본다.
 */
class ImageCleanupLockTest {

    private final ResultSet result = mock(ResultSet.class);
    private final AtomicBoolean ran = new AtomicBoolean();

    @Test
    void 잠금이_오류_NULL_을_돌려주면_정리를_돌리지_않고_던진다() throws Exception {
        given(result.next()).willReturn(true);
        given(result.getInt(1)).willReturn(0);
        given(result.wasNull()).willReturn(true);

        assertThatThrownBy(() -> lock().runExclusively(() -> ran.getAndSet(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("사진 정리 잠금(GET_LOCK)이 오류(NULL)를 돌려줬다");
        assertThat(ran).isFalse();
    }

    @Test
    void 잠금이_결과를_돌려주지_않으면_정리를_돌리지_않고_던진다() throws Exception {
        given(result.next()).willReturn(false);

        assertThatThrownBy(() -> lock().runExclusively(() -> ran.getAndSet(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("사진 정리 잠금(GET_LOCK)이 결과를 돌려주지 않았다");
        assertThat(ran).isFalse();
    }

    private ImageCleanupLock lock() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        given(statement.executeQuery()).willReturn(result);
        Connection connection = mock(Connection.class);
        given(connection.prepareStatement(anyString())).willReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        given(dataSource.getConnection()).willReturn(connection);
        return new ImageCleanupLock(dataSource);
    }
}
