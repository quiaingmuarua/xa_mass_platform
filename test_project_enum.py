#!/usr/bin/env python3
"""
测试项目枚举和全局配置功能
"""

import requests
import json

BASE_URL = "http://localhost:8080"

def test_project_enum():
    """测试项目枚举功能"""
    print("=== 测试项目枚举功能 ===")
    
    # 1. 获取项目列表
    print("\n1. 获取项目列表:")
    try:
        response = requests.get(f"{BASE_URL}/api/config/projects")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")
    
    # 2. 获取项目代码列表
    print("\n2. 获取项目代码列表:")
    try:
        response = requests.get(f"{BASE_URL}/api/config/projects/codes")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")
    
    # 3. 验证项目代码
    print("\n3. 验证项目代码 'demoApp':")
    try:
        response = requests.get(f"{BASE_URL}/api/config/projects/validate/demoApp")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")

def test_add_project():
    """测试添加项目功能"""
    print("\n=== 测试添加项目功能 ===")
    
    # 添加新项目
    new_project = {
        "code": "testApp",
        "name": "测试应用"
    }
    
    print(f"\n添加项目: {new_project}")
    try:
        response = requests.post(
            f"{BASE_URL}/api/config/projects",
            headers={"Content-Type": "application/json"},
            json=new_project
        )
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")
    
    # 再次获取项目列表，查看是否添加成功
    print("\n重新获取项目列表:")
    try:
        response = requests.get(f"{BASE_URL}/api/config/projects")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")

def test_delete_project():
    """测试删除项目功能"""
    print("\n=== 测试删除项目功能 ===")
    
    # 删除刚才添加的项目
    project_code = "testApp"
    print(f"\n删除项目: {project_code}")
    try:
        response = requests.delete(f"{BASE_URL}/api/config/projects/{project_code}")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")
    
    # 尝试删除默认项目（应该失败）
    print(f"\n尝试删除默认项目 'demoApp':")
    try:
        response = requests.delete(f"{BASE_URL}/api/config/projects/demoApp")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            data = response.json()
            print(f"响应: {json.dumps(data, indent=2, ensure_ascii=False)}")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")

def test_config_page():
    """测试配置页面访问"""
    print("\n=== 测试配置页面访问 ===")
    
    try:
        response = requests.get(f"{BASE_URL}/config")
        print(f"状态码: {response.status_code}")
        if response.status_code == 200:
            print("配置页面访问成功")
            # 检查页面内容是否包含项目管理相关元素
            content = response.text
            if "项目管理" in content and "新增项目" in content:
                print("✓ 页面包含项目管理功能")
            else:
                print("✗ 页面缺少项目管理功能")
        else:
            print(f"错误: {response.text}")
    except Exception as e:
        print(f"请求失败: {e}")

if __name__ == "__main__":
    print("开始测试项目枚举和全局配置功能...")
    
    # 等待应用启动
    import time
    print("等待应用启动...")
    time.sleep(5)
    
    test_project_enum()
    test_add_project()
    test_delete_project()
    test_config_page()
    
    print("\n测试完成！") 