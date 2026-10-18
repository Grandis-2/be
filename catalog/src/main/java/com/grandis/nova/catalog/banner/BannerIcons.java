package com.grandis.nova.catalog.banner;

import java.util.Set;

/**
 * 배너 탭에 쓸 수 있는 아이콘 — lucide 아이콘 이름(lucide-react 의 컴포넌트 이름 그대로). 화면이 이 이름으로 아이콘을 그리므로 서버는 이 목록만
 * 받는다(2026-10-18 결정). 아이콘을 더하려면 이 목록과 화면의 아이콘 대응을 같이 고친다.
 */
public final class BannerIcons {

    public static final Set<String> ALLOWED = Set.of(
            "Smartphone", "Tablet", "Laptop", "Watch", "Headphones", "Camera", "Gamepad2", "BookOpen", "MessageSquareText", "Gift",
            "Sparkles", "Star", "Tag", "Percent", "Truck", "Zap", "Bell", "CalendarDays", "ShoppingBag", "Heart");

    private BannerIcons() {
    }
}
