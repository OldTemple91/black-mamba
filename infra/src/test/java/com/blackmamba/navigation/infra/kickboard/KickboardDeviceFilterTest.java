package com.blackmamba.navigation.infra.kickboard;

import com.blackmamba.navigation.infra.kickboard.dto.KickboardDevice;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KickboardDeviceFilterTest {

    private final KickboardDeviceFilter filter = new KickboardDeviceFilter();

    @Test
    void 반경내_킥보드를_배터리_조건과_함께_필터링한다() {
        List<KickboardDevice> devices = List.of(
                new KickboardDevice("DEV_001", "씽씽", 37.5040, 127.0052, 85),
                new KickboardDevice("DEV_002", "킥고잉", 37.5900, 127.1000, 90), // 반경 밖
                new KickboardDevice("DEV_003", "Lime", 37.5041, 127.0051, 10)   // 배터리 부족(20% 미만)
        );

        List<KickboardDevice> nearby = filter.filterNearby(devices, 37.5040, 127.0050, 300);

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).deviceId()).isEqualTo("DEV_001");
    }

    @Test
    void 배터리가_20퍼센트_미만인_기기는_제외한다() {
        List<KickboardDevice> devices = List.of(
                new KickboardDevice("DEV_001", "씽씽", 37.5040, 127.0050, 19),  // 19% — 제외
                new KickboardDevice("DEV_002", "씽씽", 37.5040, 127.0050, 20)   // 20% — 포함
        );

        List<KickboardDevice> nearby = filter.filterNearby(devices, 37.5040, 127.0050, 300);

        assertThat(nearby).hasSize(1);
        assertThat(nearby.get(0).deviceId()).isEqualTo("DEV_002");
    }
}
